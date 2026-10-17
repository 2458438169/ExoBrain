package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.models.ModelDefinition;
import com.kama.jchatmind.models.ModelException;
import com.kama.jchatmind.models.ModelRuntimeService;
import com.kama.jchatmind.retrieval.ChunkDraft;
import com.kama.jchatmind.retrieval.IndexCandidate;
import com.kama.jchatmind.retrieval.IndexScope;
import com.kama.jchatmind.retrieval.IndexVersion;
import com.kama.jchatmind.retrieval.RetrievalIndexService;
import com.kama.jchatmind.retrieval.SourceDocument;
import com.kama.jchatmind.retrieval.SourceText;
import com.kama.jchatmind.service.DocumentStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 一次导入执行：解析与外部 Embedding 全部在数据库事务之外运行，只有领取、续租、发布/终态是短语句或短事务。
 *
 * <p>顺序：资源复核 → 读取已保存文件（受控路径 + 大小上限 + 源 hash 比对）→ 解析切块（03 {code prepareDocument}）
 * → 逐 chunk 调用向量模型（同时续租与上报真实进度）→ 发布闸门（{@link IngestionPublisher}）。
 * 任何一步失败都不会留下半份索引：候选只在发布事务里写库。</p>
 *
 * <p>重复执行的限制：worker 崩溃后任务会被重新领取，<b>已经发生的向量调用不会回滚</b>，可能产生额外费用；
 * 本任务不声称外部调用 exactly-once，只保证最终发布不产生重复 chunk（03 的版本与唯一索引保证）。</p>
 */
@Component
@Slf4j
public class IngestionJobRunner {

    private final IngestionJobRepository jobs;
    private final IngestionAccessGuard guard;
    private final IngestionPublisher publisher;
    private final RetrievalIndexService indexService;
    private final ModelCatalogService catalog;
    private final ModelRuntimeService runtime;
    private final DocumentStorageService storage;
    private final IngestionSettings settings;

    public IngestionJobRunner(IngestionJobRepository jobs, IngestionAccessGuard guard, IngestionPublisher publisher,
                              RetrievalIndexService indexService, ModelCatalogService catalog,
                              ModelRuntimeService runtime, DocumentStorageService storage, IngestionSettings settings) {
        this.jobs = jobs;
        this.guard = guard;
        this.publisher = publisher;
        this.indexService = indexService;
        this.catalog = catalog;
        this.runtime = runtime;
        this.storage = storage;
        this.settings = settings;
    }

    /** 执行一条已领取的任务；不抛出异常（状态写回失败只记录日志）。 */
    public void run(IngestionJob claimed) {
        long started = System.nanoTime();
        try {
            IngestionErrorCode blocked = guard.evaluate(claimed);
            if (blocked != null) {
                finish(claimed, blocked);
                return;
            }
            SourceFile file = readSource(claimed);
            if (!file.source().sha256().equalsIgnoreCase(claimed.sourceHash())) {
                finish(claimed, IngestionErrorCode.FILE_CHANGED);
                return;
            }
            IndexScope scope = indexService.resolveScope(claimed.kbId());
            if (scope.indexVersion() == IndexVersion.LEGACY_TITLE_V1) {
                publisher.publishLegacy(claimed, file.source(), file.raw());
                log.info("导入任务完成(legacy): jobId={}, ms={}", claimed.id(), elapsedMs(started));
                return;
            }
            SourceDocument source = new SourceDocument(claimed.documentId(), displayName(claimed), file.source());
            IndexCandidate candidate;
            try {
                candidate = indexService.prepareDocument(claimed.kbId(), source);
            } catch (com.kama.jchatmind.retrieval.IndexingException rejected) {
                // 模型绑定缺失/停用等属于终态，不当作可重试的临时故障。
                throw IngestionPublisher.classify(rejected);
            }
            if (candidate.chunks().isEmpty()) {
                finish(claimed, IngestionErrorCode.NOT_INDEXABLE);
                return;
            }
            if (candidate.chunks().size() > settings.maxChunks()) {
                finish(claimed, IngestionErrorCode.TOO_MANY_CHUNKS);
                return;
            }
            int total = candidate.chunks().size();
            if (!jobs.setChunksTotal(claimed.id(), claimed.fencingToken(), total)) {
                log.info("导入任务在切块阶段失去租约: jobId={}", claimed.id());
                return;
            }
            IndexCandidate embedded = embedWithProgress(claimed, candidate);
            publisher.publish(claimed, source, embedded);
            log.info("导入任务完成: jobId={}, documentId={}, chunks={}, attempts={}, ms={}",
                    claimed.id(), claimed.documentId(), total, claimed.attempts(), elapsedMs(started));
        } catch (IngestionFailure failure) {
            transition(claimed, failure.code(), failure.getMessage());
        } catch (ModelException modelFailure) {
            transition(claimed, classifyModelFailure(claimed, modelFailure), modelFailure.getMessage());
        } catch (RuntimeException unexpected) {
            log.warn("导入任务异常: jobId={}, reason={}", claimed.id(), unexpected.getClass().getSimpleName());
            transition(claimed, IngestionErrorCode.INTERNAL_ERROR, unexpected.getClass().getSimpleName());
        }
    }

    private IndexCandidate embedWithProgress(IngestionJob claimed, IndexCandidate candidate) {
        ModelDefinition model;
        try {
            model = catalog.embeddingForKnowledgeBase(claimed.kbId());
        } catch (ModelException unavailable) {
            throw new IngestionFailure(IngestionErrorCode.MODEL_UNAVAILABLE, unavailable.getMessage());
        }
        if (!model.id().equals(candidate.modelCatalogId())) {
            throw new IngestionFailure(IngestionErrorCode.MODEL_DISABLED, "model binding changed");
        }
        List<float[]> vectors = new ArrayList<>(candidate.chunks().size());
        int done = 0;
        for (ChunkDraft chunk : candidate.chunks()) {
            vectors.add(runtime.embed(model, chunk.embeddingText()));
            done++;
            if (!jobs.renew(claimed.id(), claimed.fencingToken(), done, candidate.chunks().size(),
                    settings.leaseSeconds())) {
                // 续租失败 = 租约已被重新领取：立刻停止，绝不写回任何状态或结果。
                throw new IngestionFailure(IngestionErrorCode.LEASE_LOST);
            }
        }
        return candidate.withEmbeddings(vectors);
    }

    private void transition(IngestionJob claimed, IngestionErrorCode code, String internalDetail) {
        if (code == IngestionErrorCode.LEASE_LOST) {
            log.info("导入任务租约已失效，旧 worker 不再写回: jobId={}", claimed.id());
            return;
        }
        if (code.cancelling()) {
            finish(claimed, code);
            return;
        }
        boolean retryable = code.retryable()
                && IngestionRetryPolicy.hasAttemptsLeft(claimed.attempts(), claimed.maxAttempts());
        if (!retryable) {
            finish(claimed, code == IngestionErrorCode.INTERNAL_ERROR ? IngestionErrorCode.INTERNAL_ERROR : code);
            return;
        }
        int delay = IngestionRetryPolicy.delaySeconds(claimed.attempts(), settings.retryBaseSeconds(),
                settings.retryMaxSeconds());
        if (!jobs.retryLater(claimed.id(), claimed.fencingToken(), code, delay)) {
            log.info("导入任务重试写回被 fencing 拒绝: jobId={}", claimed.id());
            return;
        }
        log.warn("导入任务临时失败，{}s 后重试: jobId={}, code={}, attempt={}/{}, detail={}",
                delay, claimed.id(), code.name(), claimed.attempts(), claimed.maxAttempts(),
                internalDetail == null ? "" : internalDetail);
    }

    private void finish(IngestionJob claimed, IngestionErrorCode code) {
        IngestionStatus target = code.cancelling() ? IngestionStatus.CANCELLED : IngestionStatus.FAILED;
        if (!jobs.finish(claimed.id(), claimed.fencingToken(), target, code)) {
            log.info("导入任务终态写回被 fencing 拒绝: jobId={}, code={}", claimed.id(), code.name());
        }
    }

    private IngestionErrorCode classifyModelFailure(IngestionJob claimed, ModelException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.contains("停用") || message.contains("尚未绑定")) {
            return IngestionErrorCode.MODEL_DISABLED;
        }
        if (failure.getStatus() == 400 || failure.getStatus() == 404) {
            return IngestionErrorCode.EMBEDDING_REJECTED;
        }
        if (failure.getStatus() == 502 || failure.getStatus() == 503) {
            return IngestionErrorCode.EMBEDDING_UNAVAILABLE;
        }
        return IngestionErrorCode.EMBEDDING_UNAVAILABLE;
    }

    /** 从数据库里的受控相对路径读取文件；路径不由请求提供，并且仍有形状、大小与符号链接检查。 */
    private SourceFile readSource(IngestionJob job) {
        String relativePath = validRelativePath(job.filePath(), job.kbId(), job.documentId())
                .orElseThrow(() -> new IngestionFailure(IngestionErrorCode.UNSUPPORTED_TYPE));
        Path file = storage.getFilePath(relativePath);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IngestionFailure(IngestionErrorCode.FILE_UNREADABLE);
            }
            if (Files.size(file) > settings.maxFileBytes()) {
                throw new IngestionFailure(IngestionErrorCode.FILE_TOO_LARGE);
            }
            byte[] raw = Files.readAllBytes(file);
            if (raw.length > settings.maxFileBytes()) {
                throw new IngestionFailure(IngestionErrorCode.FILE_TOO_LARGE);
            }
            return new SourceFile(raw, SourceText.normalize(raw));
        } catch (IOException unreadable) {
            throw new IngestionFailure(IngestionErrorCode.FILE_UNREADABLE, unreadable.getClass().getSimpleName());
        }
    }

    /**
     * 校验 {@code kbId/documentId/filename} 形状，避免任何绝对路径、反斜杠、盘符或额外层级。
     * 与 {@code DocumentStorageServiceImpl.readMarkdown} 的路径边界一致（不跟随符号链接）。
     */
    static java.util.Optional<String> validRelativePath(String filePath, String kbId, String documentId) {
        if (filePath == null || filePath.isBlank() || filePath.indexOf('\\') >= 0 || filePath.indexOf(':') >= 0) {
            return java.util.Optional.empty();
        }
        Path relative = Paths.get(filePath);
        if (relative.isAbsolute() || relative.getNameCount() != 3) {
            return java.util.Optional.empty();
        }
        String kb = canonical(kbId);
        String document = canonical(documentId);
        if (kb.isEmpty() || document.isEmpty()
                || !kb.equalsIgnoreCase(relative.getName(0).toString())
                || !document.equalsIgnoreCase(relative.getName(1).toString())) {
            return java.util.Optional.empty();
        }
        String name = relative.getName(2).toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".md") && !name.endsWith(".markdown")) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(relative.toString().replace('\\', '/'));
    }

    private static String canonical(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (RuntimeException invalid) {
            return "";
        }
    }

    private String displayName(IngestionJob job) {
        try {
            return Paths.get(job.filePath()).getName(2).toString();
        } catch (RuntimeException invalid) {
            return job.documentId();
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private record SourceFile(byte[] raw, SourceText source) {
    }
}
