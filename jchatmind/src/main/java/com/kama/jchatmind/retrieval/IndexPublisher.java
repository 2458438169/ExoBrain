package com.kama.jchatmind.retrieval;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.models.ModelDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 索引发布的写路径：候选与活动版本的隔离、原子切换与回滚。
 *
 * <p>发布是一个短事务：先 {@code SELECT ... FOR UPDATE} 锁住 knowledge_base 行（同时挡住并发发布与 KB 删除），
 * 再重新核对候选与线上状态；核对失败就抛异常，事务回滚，旧索引继续可用。切换本身只是
 * “插入新版本 chunk → 新版本标记 PUBLISHED → 旧版本 SUPERSEDED → 更新活动状态行”四步，
 * 因此检索要么看到旧版本、要么看到新版本，不会读到混合结果。</p>
 *
 * <p>删除与并发：文档在快照之后被删除/新增会让候选文档集合与线上不一致，发布被拒绝（任务 06 据此安全重建）；
 * chunk 对 document 的外键是级联，即使删除刚好发生在发布事务提交前后，也不会留下可检索的孤儿索引。</p>
 */
@Service
@Slf4j
public class IndexPublisher {

    private final RetrievalIndexRepository repository;
    private final ChunkBgeM3Mapper chunks;
    private final ModelCatalogService catalog;

    public IndexPublisher(RetrievalIndexRepository repository, ChunkBgeM3Mapper chunks, ModelCatalogService catalog) {
        this.repository = repository;
        this.chunks = chunks;
        this.catalog = catalog;
    }

    @Transactional
    public PublishedIndex publish(IndexCandidate candidate) {
        String kbId = canonicalId(candidate.kbId(), "知识库 id 无效");
        if (!candidate.embeddingsAttached()) {
            throw new IndexingException("候选索引还没有向量，不能发布");
        }
        if (!repository.lockKnowledgeBase(kbId)) {
            throw new IndexingException("知识库不存在，索引发布已取消");
        }

        KbIndexState state = repository.findState(kbId).orElse(null);
        List<String> liveDocuments = repository.listDocumentIds(kbId);
        Set<String> expected = lowerCase(candidate.sourceDocumentIds());
        Set<String> live = lowerCase(liveDocuments);
        if (!expected.equals(live)) {
            throw new IndexingException("候选构建之后文档清单已变化，本次发布已取消");
        }
        if (candidate.sourceSetHash() != null
                && !candidate.sourceSetHash().equals(SourceSets.hash(candidate.sources()))) {
            throw new IndexingException("候选索引的文档清单摘要与内容不一致，本次发布已取消");
        }

        Set<String> freshDocuments = lowerCase(candidate.chunkDocumentIds());
        if (!expected.containsAll(freshDocuments)) {
            throw new IndexingException("候选索引包含不属于本次候选的文档 chunk，本次发布已取消");
        }
        String baseRevisionId = candidate.baseRevisionId();
        boolean copiesBaseChunks = freshDocuments.size() < expected.size();
        if (copiesBaseChunks) {
            if (baseRevisionId == null) {
                throw new IndexingException("候选索引缺少基础版本，无法发布部分重建的索引");
            }
            if (state == null || state.activeRevisionId() == null
                    || !state.activeRevisionId().equalsIgnoreCase(baseRevisionId)) {
                throw new IndexingException("活动索引版本在候选构建期间已被改写，本次发布已取消");
            }
            Set<String> baseDocuments = lowerCase(documentIds(repository.loadRevisionSourceSet(baseRevisionId)));
            int copyable = 0;
            for (String documentId : expected) {
                if (!freshDocuments.contains(documentId) && baseDocuments.contains(documentId)) {
                    copyable++;
                }
            }
            if (copyable != expected.size() - freshDocuments.size()) {
                throw new IndexingException("基础版本没有覆盖候选中的全部文档，本次发布已取消");
            }
        }

        ModelDefinition bound = requireEmbeddingModel(kbId);
        if (candidate.modelCatalogId() == null || !candidate.modelCatalogId().equals(bound.id())) {
            throw new IndexingException("知识库绑定的向量模型已变化，本次发布已取消");
        }

        String revisionId = repository.insertRevision(kbId, candidate.indexVersion(),
                IndexRevision.IndexStatus.DRAFT, candidate.modelCatalogId(), candidate.modelName(),
                candidate.dimensions());
        int copied = 0;
        if (copiesBaseChunks) {
            copied = repository.copyChunks(baseRevisionId, revisionId, candidate.indexVersion(), freshDocuments);
        }
        Map<String, String> names = repository.documentNames(expected);
        LocalDateTime now = LocalDateTime.now();
        List<ChunkDraft> drafts = candidate.chunks();
        List<float[]> vectors = candidate.embeddings();
        for (int index = 0; index < drafts.size(); index++) {
            ChunkDraft draft = drafts.get(index);
            String documentName = names.get(draft.documentId().toLowerCase(Locale.ROOT));
            ChunkBgeM3 row = ChunkBgeM3.builder()
                    .kbId(kbId)
                    .docId(draft.documentId())
                    .content(draft.content())
                    .metadata(ChunkMetadata.encode(draft, candidate.indexVersion(), documentName))
                    .embedding(vectors.get(index))
                    .revisionId(revisionId)
                    .indexVersion(candidate.indexVersion().wire())
                    .chunkOrdinal(draft.ordinal())
                    .sourceHash(draft.sourceHash())
                    .startLine(draft.startLine())
                    .endLine(draft.endLine())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            if (chunks.insertIndexed(row) <= 0) {
                throw new IndexingException("写入索引 chunk 失败，本次发布已取消");
            }
        }

        int chunkCount = repository.countChunksForRevision(revisionId);
        if (chunkCount <= 0) {
            throw new IndexingException("候选索引没有可发布的 chunk，本次发布已取消");
        }
        // 原子切换：先让旧版本 SUPERSEDED，再把新版本置为 PUBLISHED（exobrain_index_revision_active
        // 唯一索引保证每个 KB 最多一个活动版本；两个 UPDATE 在同一事务内，检索侧只会看到切换前或切换后状态）。
        if (state != null && state.activeRevisionId() != null
                && !state.activeRevisionId().equalsIgnoreCase(revisionId)) {
            repository.supersedeRevision(state.activeRevisionId());
        }
        repository.publishRevision(revisionId, expected.size(), chunkCount, SourceSets.hash(candidate.sources()));
        repository.upsertState(kbId, revisionId, candidate.indexVersion());
        IndexRevision revision = repository.findRevision(revisionId)
                .orElseThrow(() -> new IndexingException("索引版本写入后不可读，本次发布已取消"));
        log.info("索引发布完成: kbId={}, revisionId={}, indexVersion={}, documents={}, chunks={}, copied={}",
                kbId, revisionId, candidate.indexVersion().wire(), expected.size(), chunkCount, copied);
        return new PublishedIndex(revision, new IndexScope(candidate.indexVersion(), revisionId),
                expected.size(), chunkCount);
    }

    /**
     * 回滚到一个仍然存在数据的已发布版本。绝不指向已被清理的数据：目标版本必须曾经发布成功且仍有 chunk；
     * 当前活动版本只在切换成功后才会被标记 SUPERSEDED。
     */
    @Transactional
    public PublishedIndex rollback(String kbId, String revisionId) {
        String canonicalKb = canonicalId(kbId, "知识库 id 无效");
        String canonicalRevision = canonicalId(revisionId, "索引版本 id 无效");
        if (!repository.lockKnowledgeBase(canonicalKb)) {
            throw new IndexingException("知识库不存在，索引回滚已取消");
        }
        IndexRevision revision = repository.findRevision(canonicalRevision)
                .orElseThrow(() -> new IndexingException("索引版本不存在"));
        if (!revision.kbId().equalsIgnoreCase(canonicalKb)) {
            throw new IndexingException("索引版本不属于该知识库");
        }
        if (revision.status() == IndexRevision.IndexStatus.DRAFT || revision.status() == IndexRevision.IndexStatus.FAILED) {
            throw new IndexingException("该版本从未成功发布，不能回滚到它");
        }
        int chunkCount = repository.countChunksForRevision(canonicalRevision);
        if (chunkCount == 0) {
            throw new IndexingException("该版本的数据已被清理，不能回滚到它");
        }
        KbIndexState state = repository.findState(canonicalKb).orElse(null);
        if (state != null && canonicalRevision.equalsIgnoreCase(String.valueOf(state.activeRevisionId()))) {
            return new PublishedIndex(revision, new IndexScope(revision.indexVersion(), canonicalRevision),
                    revision.documentCount(), chunkCount);
        }
        requireEmbeddingModel(canonicalKb);
        // 与发布相同的原子切换顺序：先 SUPERSEDED 当前活动版本，再重新激活目标版本（唯一索引约束）。
        if (state != null && state.activeRevisionId() != null) {
            repository.supersedeRevision(state.activeRevisionId());
        }
        repository.activateRevision(canonicalRevision);
        repository.upsertState(canonicalKb, canonicalRevision, revision.indexVersion());
        IndexRevision active = repository.findRevision(canonicalRevision)
                .orElseThrow(() -> new IndexingException("索引版本切换后不可读"));
        log.info("索引回滚完成: kbId={}, revisionId={}, indexVersion={}, chunks={}",
                canonicalKb, canonicalRevision, revision.indexVersion().wire(), chunkCount);
        return new PublishedIndex(active, new IndexScope(revision.indexVersion(), canonicalRevision),
                revision.documentCount(), chunkCount);
    }

    /** 清理被替换的版本（保留最近 keepNewest 个）；活动版本永远不会被删。 */
    @Transactional
    public int cleanupSuperseded(String kbId, int keepNewest) {
        String canonicalKb = canonicalId(kbId, "知识库 id 无效");
        int deleted = repository.deleteSupersededRevisions(canonicalKb, Math.max(0, keepNewest));
        if (deleted > 0) {
            log.info("清理已替换的索引版本: kbId={}, deleted={}", canonicalKb, deleted);
        }
        return deleted;
    }

    /** 文档删除后刷新活动版本的计数（计数是事实，不会留着旧数字；来源摘要置空而不是伪造）。 */
    @Transactional
    public void refreshActiveRevision(String kbId) {
        String canonicalKb = canonicalId(kbId, "知识库 id 无效");
        int updated = repository.refreshActiveRevision(canonicalKb);
        if (updated > 0) {
            log.info("文档删除后刷新活动索引版本计数: kbId={}", canonicalKb);
        }
    }

    /**
     * 登记一次失败的构建尝试（FAILED 版本，不含任何正文），不改变活动版本。
     * 只应在发布事务之外调用（离线/受控重建路径）。
     */
    @Transactional
    public String recordFailure(String kbId, IndexVersion version, ModelDefinition model) {
        String canonicalKb = canonicalId(kbId, "知识库 id 无效");
        if (!repository.knowledgeBaseExists(canonicalKb)) {
            return null;
        }
        return repository.insertRevision(canonicalKb, version, IndexRevision.IndexStatus.FAILED,
                model == null ? null : model.id(), model == null ? "unknown" : model.model(),
                model == null || model.dimensions() == null ? 1024 : model.dimensions());
    }

    private ModelDefinition requireEmbeddingModel(String kbId) {
        try {
            return catalog.embeddingForKnowledgeBase(kbId);
        } catch (RuntimeException unavailable) {
            throw new IndexingException("知识库没有可用的向量模型绑定，索引操作已取消");
        }
    }

    private static List<String> documentIds(List<SourceRef> refs) {
        return refs.stream().map(SourceRef::documentId).toList();
    }

    private static Set<String> lowerCase(List<String> ids) {
        Set<String> result = new HashSet<>();
        for (String id : ids) {
            result.add(id.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    static String canonicalId(String id, String message) {
        try {
            if (id == null || !UUID.fromString(id).toString().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IndexingException(message);
        }
    }
}