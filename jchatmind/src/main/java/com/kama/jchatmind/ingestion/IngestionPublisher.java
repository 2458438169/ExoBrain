package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.retrieval.IndexCandidate;
import com.kama.jchatmind.retrieval.IndexingException;
import com.kama.jchatmind.retrieval.LegacyTitleIndexer;
import com.kama.jchatmind.retrieval.PublishedIndex;
import com.kama.jchatmind.retrieval.RetrievalIndexService;
import com.kama.jchatmind.retrieval.SourceDocument;
import com.kama.jchatmind.retrieval.SourceText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * 发布闸门：只有当前有效租约持有者能在<b>短事务</b>内确认源 hash、索引版本与资源状态后发布。
 *
 * <p>事务里依次做：</p>
 * <ol>
 *   <li>{@code SELECT ... FOR UPDATE} 锁住任务行，核对 {@code status=RUNNING}、{@code fencing_token}、
 *       {@code lease_owner} 与租约未过期——过期租约的旧 worker 即使晚收到模型响应也写不回来；</li>
 *   <li>重新检查账号状态、KB 归属、文档存在与文件 hash（不是只在任务开始时检查一次）；</li>
 *   <li>调用 03 的 {@link RetrievalIndexService#publish}：它会在同一事务里再核一次文档清单、
 *       基础版本与模型绑定，然后原子切换活动版本；任何一步失败都保持旧索引可用。</li>
 * </ol>
 *
 * <p>这里不调用任何外部服务：Embedding 已由 worker 在事务外完成（重复执行的向量调用可能产生额外费用，
 * 见 ADR 0006，不声称 exactly-once）。</p>
 */
@Service
@Slf4j
public class IngestionPublisher {

    private final IngestionJobRepository jobs;
    private final IngestionAccessGuard guard;
    private final RetrievalIndexService indexService;
    private final LegacyTitleIndexer legacyIndexer;

    public IngestionPublisher(IngestionJobRepository jobs, IngestionAccessGuard guard,
                              RetrievalIndexService indexService, LegacyTitleIndexer legacyIndexer) {
        this.jobs = jobs;
        this.guard = guard;
        this.indexService = indexService;
        this.legacyIndexer = legacyIndexer;
    }

    @Transactional
    public PublishedIndex publish(IngestionJob claimed, SourceDocument source, IndexCandidate candidate) {
        IngestionJob locked = requireLease(claimed);
        requireLiveResources(locked);
        if (!locked.kbId().equalsIgnoreCase(candidate.kbId())) {
            throw new IngestionFailure(IngestionErrorCode.PUBLISH_REJECTED);
        }
        if (!containsDocument(candidate, locked.documentId())) {
            throw new IngestionFailure(IngestionErrorCode.PUBLISH_REJECTED);
        }
        if (!source.documentId().equalsIgnoreCase(locked.documentId())
                || !source.sourceHash().equalsIgnoreCase(locked.sourceHash())) {
            throw new IngestionFailure(IngestionErrorCode.FILE_CHANGED);
        }
        PublishedIndex published;
        try {
            published = indexService.publish(candidate);
        } catch (IndexingException rejected) {
            throw classify(rejected);
        }
        if (!jobs.succeed(locked.id(), claimed.fencingToken(), published.revision().id(), published.chunkCount())) {
            // 同一事务内被拒绝：任务已被别的 worker 接手或已被取消，索引切换一并回滚。
            throw new IngestionFailure(IngestionErrorCode.LEASE_LOST);
        }
        log.info("导入任务发布完成: jobId={}, documentId={}, revisionId={}, chunks={}",
                locked.id(), locked.documentId(), published.revision().id(), published.chunkCount());
        return published;
    }

    /** legacy-title-v1 库保持基线行为（标题向量、无位置），仍复用同一套租约与资源复核。 */
    @Transactional
    public int publishLegacy(IngestionJob claimed, SourceText source, byte[] raw) {
        IngestionJob locked = requireLease(claimed);
        requireLiveResources(locked);
        if (!source.sha256().equalsIgnoreCase(locked.sourceHash())) {
            throw new IngestionFailure(IngestionErrorCode.FILE_CHANGED);
        }
        int chunks = legacyIndexer.index(locked.kbId(), locked.documentId(), raw);
        if (chunks <= 0) {
            throw new IngestionFailure(IngestionErrorCode.NOT_INDEXABLE);
        }
        if (!jobs.succeed(locked.id(), claimed.fencingToken(), null, chunks)) {
            throw new IngestionFailure(IngestionErrorCode.LEASE_LOST);
        }
        log.info("legacy 库导入完成: jobId={}, documentId={}, chunks={}", locked.id(), locked.documentId(), chunks);
        return chunks;
    }

    private IngestionJob requireLease(IngestionJob claimed) {
        IngestionJob locked = jobs.lockById(claimed.id())
                .orElseThrow(() -> new IngestionFailure(IngestionErrorCode.DOCUMENT_DELETED));
        if (locked.status() != IngestionStatus.RUNNING
                || locked.fencingToken() != claimed.fencingToken()
                || locked.leaseOwner() == null
                || !locked.leaseOwner().equals(claimed.leaseOwner())
                || locked.leaseExpiresAt() == null
                || !locked.leaseExpiresAt().isAfter(Instant.now())) {
            throw new IngestionFailure(IngestionErrorCode.LEASE_LOST);
        }
        return locked;
    }


    private void requireLiveResources(IngestionJob job) {
        IngestionErrorCode blocked = guard.evaluate(job);
        if (blocked != null) {
            throw new IngestionFailure(blocked);
        }
    }

    private static boolean containsDocument(IndexCandidate candidate, String documentId) {
        return candidate.sourceDocumentIds().stream()
                .anyMatch(id -> id != null && id.toLowerCase(Locale.ROOT).equals(documentId.toLowerCase(Locale.ROOT)));
    }

    /** 03 的拒绝原因映射成有限的错误分类；不把原始异常文本返回给用户。 */
    static IngestionFailure classify(IndexingException rejected) {
        String message = rejected.getMessage() == null ? "" : rejected.getMessage();
        if (message.contains("文档清单已变化") || message.contains("活动索引版本在候选构建期间已被改写")
                || message.contains("基础版本没有覆盖")) {
            return new IngestionFailure(IngestionErrorCode.INDEX_CONFLICT, message);
        }
        if (message.contains("知识库不存在")) {
            return new IngestionFailure(IngestionErrorCode.KNOWLEDGE_BASE_DELETED, message);
        }
        if (message.contains("向量模型")) {
            return new IngestionFailure(IngestionErrorCode.MODEL_UNAVAILABLE, message);
        }
        if (message.contains("没有可发布的 chunk") || message.contains("没有可索引的章节")) {
            return new IngestionFailure(IngestionErrorCode.NOT_INDEXABLE, message);
        }
        return new IngestionFailure(IngestionErrorCode.PUBLISH_FAILED, message);
    }
}
