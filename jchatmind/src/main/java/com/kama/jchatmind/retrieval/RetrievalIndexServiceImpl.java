package com.kama.jchatmind.retrieval;

import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.models.ModelDefinition;
import com.kama.jchatmind.models.ModelException;
import com.kama.jchatmind.models.ModelRuntimeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 索引构建与发布的编排实现：只读快照 + 事务外 Embedding + 短事务发布。
 *
 * <p>写路径委托给 {@link IndexPublisher}（独立的 Spring Bean，保证 {@code @Transactional} 代理生效，
 * 不会因为同类内部调用退化成自动提交）。</p>
 */
@Service
@Slf4j
public class RetrievalIndexServiceImpl implements RetrievalIndexService {

    private final RetrievalIndexRepository repository;
    private final IndexPublisher publisher;
    private final ModelCatalogService catalog;
    private final ModelRuntimeService runtime;
    private final RetrievalSettings settings;
    private final BodyChunker chunker = new BodyChunker();

    public RetrievalIndexServiceImpl(RetrievalIndexRepository repository, IndexPublisher publisher,
                                     ModelCatalogService catalog, ModelRuntimeService runtime,
                                     RetrievalSettings settings) {
        this.repository = repository;
        this.publisher = publisher;
        this.catalog = catalog;
        this.runtime = runtime;
        this.settings = settings;
    }

    @Override
    public IndexScope resolveScope(String kbId) {
        String canonicalKb = IndexPublisher.canonicalId(kbId, "知识库 id 无效");
        return IndexVersionPolicy.resolve(repository.findState(canonicalKb).orElse(null),
                repository.hasLegacyChunks(canonicalKb));
    }

    @Override
    public IndexCandidate prepareRebuild(String kbId, List<SourceDocument> documents) {
        String canonicalKb = IndexPublisher.canonicalId(kbId, "知识库 id 无效");
        ModelDefinition model = requireEmbeddingModel(canonicalKb);
        List<ChunkDraft> drafts = new ArrayList<>();
        List<SourceRef> refs = new ArrayList<>();
        for (SourceDocument document : documents) {
            drafts.addAll(chunker.chunk(document, settings.chunkBudget()));
            refs.add(new SourceRef(document.documentId(), document.sourceHash()));
        }
        return new IndexCandidate(canonicalKb, IndexVersion.BODY_V1, model.id(), model.model(),
                dimensions(model), refs, drafts, null, null, SourceSets.hash(refs));
    }

    @Override
    public IndexCandidate prepareDocument(String kbId, SourceDocument document) {
        String canonicalKb = IndexPublisher.canonicalId(kbId, "知识库 id 无效");
        IndexScope base = resolveScope(canonicalKb);
        if (base.indexVersion() == IndexVersion.LEGACY_TITLE_V1) {
            throw new IndexingException("该知识库仍在使用 legacy-title-v1 索引，请先显式重建再写入正文索引");
        }
        ModelDefinition model = requireEmbeddingModel(canonicalKb);
        List<SourceRef> refs = new ArrayList<>();
        if (base.revisionId() != null) {
            // 未变化文档的 chunk 从当前活动版本复制；新库（还没有版本）只有本次这一份文档。
            refs.addAll(repository.loadRevisionSourceSet(base.revisionId()));
        }
        refs.add(new SourceRef(document.documentId(), document.sourceHash()));
        List<ChunkDraft> drafts = chunker.chunk(document, settings.chunkBudget());
        return new IndexCandidate(canonicalKb, IndexVersion.BODY_V1, model.id(), model.model(),
                dimensions(model), refs, drafts, null, base.revisionId(), SourceSets.hash(refs));
    }

    @Override
    public IndexCandidate embedCandidate(IndexCandidate candidate) {
        ModelDefinition model = requireEmbeddingModel(candidate.kbId());
        if (!model.id().equals(candidate.modelCatalogId())) {
            throw new IndexingException("知识库绑定的向量模型已变化，候选索引已失效");
        }
        List<float[]> vectors = new ArrayList<>(candidate.chunks().size());
        for (ChunkDraft chunk : candidate.chunks()) {
            vectors.add(runtime.embed(model, chunk.embeddingText()));
        }
        return candidate.withEmbeddings(vectors);
    }

    @Override
    public PublishedIndex publish(IndexCandidate candidate) {
        return publisher.publish(candidate);
    }

    @Override
    public PublishedIndex rebuild(String kbId, List<SourceDocument> documents) {
        String canonicalKb = IndexPublisher.canonicalId(kbId, "知识库 id 无效");
        ModelDefinition model = null;
        try {
            model = catalog.embeddingForKnowledgeBase(canonicalKb);
            IndexCandidate candidate = prepareRebuild(canonicalKb, documents);
            return publisher.publish(embedCandidate(candidate));
        } catch (RuntimeException failure) {
            recordFailureQuietly(canonicalKb, model, failure);
            throw failure;
        }
    }

    @Override
    public PublishedIndex indexDocument(String kbId, SourceDocument document) {
        IndexCandidate candidate = prepareDocument(kbId, document);
        return publisher.publish(embedCandidate(candidate));
    }

    @Override
    public int cleanupSuperseded(String kbId, int keepNewestRevisions) {
        return publisher.cleanupSuperseded(kbId, keepNewestRevisions);
    }

    @Override
    public void refreshActiveRevision(String kbId) {
        publisher.refreshActiveRevision(kbId);
    }

    /** 失败尝试登记为 FAILED 版本（不改变活动版本），异常原因只进服务端日志。 */
    private void recordFailureQuietly(String kbId, ModelDefinition model, RuntimeException failure) {
        try {
            publisher.recordFailure(kbId, IndexVersion.BODY_V1, model);
        } catch (RuntimeException registrationFailed) {
            log.warn("登记索引失败版本时出错: kbId={}, reason={}", kbId, registrationFailed.getClass().getSimpleName());
        }
        log.warn("索引重建失败: kbId={}, reason={}", kbId, failure.getClass().getSimpleName());
    }

    private ModelDefinition requireEmbeddingModel(String kbId) {
        try {
            return catalog.embeddingForKnowledgeBase(kbId);
        } catch (ModelException unavailable) {
            throw new IndexingException("知识库没有可用的向量模型绑定，索引操作已取消");
        }
    }

    private static int dimensions(ModelDefinition model) {
        return model.dimensions() == null ? 1024 : model.dimensions();
    }
}