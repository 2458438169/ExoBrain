package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.models.ModelDefinition;
import com.kama.jchatmind.models.ModelException;
import com.kama.jchatmind.models.ModelKind;
import com.kama.jchatmind.models.ModelRuntimeService;
import com.kama.jchatmind.retrieval.ChunkDraft;
import com.kama.jchatmind.retrieval.IndexCandidate;
import com.kama.jchatmind.retrieval.IndexScope;
import com.kama.jchatmind.retrieval.IndexVersion;
import com.kama.jchatmind.retrieval.RetrievalIndexService;
import com.kama.jchatmind.retrieval.SourceRef;
import com.kama.jchatmind.retrieval.SourceText;
import com.kama.jchatmind.service.DocumentStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 导入状态机（无数据库）：临时失败 → RETRY_WAIT、永久错误 → FAILED、资源/权限变化 → CANCELLED，
 * 失去租约时一个状态都不写回。
 */
class IngestionJobRunnerTest {

    private static final String KB = "11111111-1111-4111-8111-111111111111";
    private static final String DOC = "22222222-2222-4222-8222-222222222222";
    private static final String JOB = "33333333-3333-4333-8333-333333333333";
    private static final String MODEL = "model-1";
    private static final String CONTENT = "# Title\nBody text for indexing.\n";

    @TempDir
    Path storageRoot;

    private final IngestionJobRepository jobs = mock(IngestionJobRepository.class);
    private final IngestionAccessGuard guard = mock(IngestionAccessGuard.class);
    private final IngestionPublisher publisher = mock(IngestionPublisher.class);
    private final RetrievalIndexService indexService = mock(RetrievalIndexService.class);
    private final ModelCatalogService catalog = mock(ModelCatalogService.class);
    private final ModelRuntimeService runtime = mock(ModelRuntimeService.class);
    private final DocumentStorageService storage = mock(DocumentStorageService.class);
    private final IngestionSettings settings = new IngestionSettings();
    private IngestionJobRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        String relative = KB + "/" + DOC + "/file.md";
        Path file = storageRoot.resolve("file.md");
        Files.write(file, CONTENT.getBytes(StandardCharsets.UTF_8));
        when(storage.getFilePath(relative)).thenReturn(file);

        IndexCandidate candidate = new IndexCandidate(KB, IndexVersion.BODY_V1, MODEL, "bge-m3", 1024,
                List.of(new SourceRef(DOC, hash())),
                List.of(new ChunkDraft(DOC, 0, List.of("Title"), 1, 3, hash(), CONTENT, "Title\n" + CONTENT)),
                null, null, "source-set");
        when(indexService.resolveScope(KB)).thenReturn(new IndexScope(IndexVersion.BODY_V1, null));
        when(indexService.prepareDocument(eq(KB), any())).thenReturn(candidate);
        when(catalog.embeddingForKnowledgeBase(KB)).thenReturn(new ModelDefinition(MODEL, "向量模型",
                ModelKind.EMBEDDING, "openai", "https://example.invalid", "bge-m3", null, true, true, 1024,
                Instant.now(), Instant.now()));
        when(jobs.setChunksTotal(anyString(), anyLong(), anyInt())).thenReturn(true);
        when(jobs.renew(anyString(), anyLong(), anyInt(), any(), anyInt())).thenReturn(true);
        when(jobs.retryLater(anyString(), anyLong(), any(), anyInt())).thenReturn(true);
        when(jobs.finish(anyString(), anyLong(), any(), any())).thenReturn(true);
        when(runtime.embed(any(), anyString())).thenReturn(new float[1024]);

        runner = new IngestionJobRunner(jobs, guard, publisher, indexService, catalog, runtime, storage, settings);
    }

    @Test
    @DisplayName("a transient embedding failure schedules a bounded retry instead of failing")
    void transientFailureSchedulesRetry() {
        when(runtime.embed(any(), anyString()))
                .thenThrow(new ModelException(502, "向量服务暂不可用，请联系管理员检查模型配置"));

        runner.run(job(1, 3));

        verify(jobs).retryLater(eq(JOB), eq(1L), eq(IngestionErrorCode.EMBEDDING_UNAVAILABLE),
                eq(settings.retryBaseSeconds()));
        verify(jobs, never()).finish(anyString(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("a rejected embedding request fails permanently without retrying")
    void permanentEmbeddingErrorIsTerminal() {
        when(runtime.embed(any(), anyString())).thenThrow(new ModelException(400, "向量查询内容不能为空"));

        runner.run(job(1, 3));

        verify(jobs).finish(eq(JOB), eq(1L), eq(IngestionStatus.FAILED), eq(IngestionErrorCode.EMBEDDING_REJECTED));
        verify(jobs, never()).retryLater(anyString(), anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("the last allowed attempt becomes FAILED instead of waiting again")
    void exhaustedAttemptsBecomeFailed() {
        when(runtime.embed(any(), anyString())).thenThrow(new ModelException(502, "暂时不可用"));

        runner.run(job(3, 3));

        verify(jobs).finish(eq(JOB), eq(1L), eq(IngestionStatus.FAILED), eq(IngestionErrorCode.EMBEDDING_UNAVAILABLE));
        verify(jobs, never()).retryLater(anyString(), anyLong(), any(), anyInt());
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("a disabled account or deleted resource cancels the job before any external work")
    void resourceChangeCancels() {
        when(guard.evaluate(any(IngestionJob.class))).thenReturn(IngestionErrorCode.ACCOUNT_DISABLED);

        runner.run(job(1, 3));

        verify(jobs).finish(eq(JOB), eq(1L), eq(IngestionStatus.CANCELLED), eq(IngestionErrorCode.ACCOUNT_DISABLED));
        verify(runtime, never()).embed(any(), anyString());
    }

    @Test
    @DisplayName("losing the lease writes no state at all so the new owner stays authoritative")
    void lostLeaseDoesNotWriteBack() {
        when(jobs.renew(anyString(), anyLong(), anyInt(), any(), anyInt())).thenReturn(false);

        runner.run(job(1, 3));

        verify(jobs, never()).finish(anyString(), anyLong(), any(), any());
        verify(jobs, never()).retryLater(anyString(), anyLong(), any(), anyInt());
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("a document whose hash changed on disk fails instead of indexing stale bytes")
    void changedFileFails() throws Exception {
        Files.write(storageRoot.resolve("file.md"), "# Different\n".getBytes(StandardCharsets.UTF_8));

        runner.run(job(1, 3));

        verify(jobs).finish(eq(JOB), eq(1L), eq(IngestionStatus.FAILED), eq(IngestionErrorCode.FILE_CHANGED));
    }

    private IngestionJob job(int attempts, int maxAttempts) {
        Instant now = Instant.now();
        return new IngestionJob(JOB, KB, DOC, KB + "/" + DOC + "/file.md", hash(), null,
                IngestionStatus.RUNNING, attempts, maxAttempts, now, "worker-1", now.plusSeconds(120), 1L,
                null, 0, null, null, null, now, now, null);
    }

    private static String hash() {
        return SourceText.normalize(CONTENT.getBytes(StandardCharsets.UTF_8)).sha256();
    }
}
