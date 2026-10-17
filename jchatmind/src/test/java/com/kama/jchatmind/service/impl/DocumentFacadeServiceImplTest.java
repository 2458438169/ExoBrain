package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.ingestion.IngestionService;
import com.kama.jchatmind.ingestion.IngestionSettings;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.retrieval.ChunkDraft;
import com.kama.jchatmind.retrieval.IndexCandidate;
import com.kama.jchatmind.retrieval.IndexScope;
import com.kama.jchatmind.retrieval.IndexVersion;
import com.kama.jchatmind.retrieval.LegacyTitleIndexer;
import com.kama.jchatmind.retrieval.RetrievalIndexService;
import com.kama.jchatmind.retrieval.SourceDocument;
import com.kama.jchatmind.retrieval.SourceRef;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.StorageCleanupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 上传路径（任务 06）：保存文件 + 文档记录 + 导入任务同事务提交，索引交给 worker 异步发布。 */
class DocumentFacadeServiceImplTest {

    private static final String KB = "11111111-1111-4111-8111-111111111111";
    private static final String DOCUMENT = "aaaaaaaa-1111-4111-8111-111111111111";
    private static final String JOB = "bbbbbbbb-1111-4111-8111-111111111111";

    @TempDir
    Path temporaryDirectory;

    private final DocumentMapper documents = mock(DocumentMapper.class);
    private final DocumentStorageService storage = mock(DocumentStorageService.class);
    private final RetrievalIndexService retrievalIndexService = mock(RetrievalIndexService.class);
    private final LegacyTitleIndexer legacyTitleIndexer = mock(LegacyTitleIndexer.class);
    private final IngestionService ingestionService = mock(IngestionService.class);
    private final TrackingTransactions transactions = new TrackingTransactions();
    private DocumentFacadeService service;
    private MockMultipartFile upload;
    private Path savedFile;

    @BeforeEach
    void setUp() throws Exception {
        byte[] contents = "# Title\nDocument content".getBytes(StandardCharsets.UTF_8);
        savedFile = temporaryDirectory.resolve("upload.md");
        Files.write(savedFile, contents);
        upload = new MockMultipartFile("file", "upload.md", "text/markdown", contents);
        when(documents.insert(any())).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setId(DOCUMENT);
            return 1;
        });
        when(documents.updateById(any())).thenReturn(1);
        when(storage.saveFile(eq(KB), eq(DOCUMENT), any())).thenReturn("kb/document/upload.md");
        when(storage.getFilePath("kb/document/upload.md")).thenReturn(savedFile);
        when(ingestionService.submit(eq(KB), eq(DOCUMENT), eq("kb/document/upload.md"), anyString(), any()))
                .thenReturn(JOB);
        when(retrievalIndexService.resolveScope(KB)).thenReturn(new IndexScope(IndexVersion.BODY_V1, null));
        when(retrievalIndexService.prepareDocument(eq(KB), any())).thenAnswer(invocation -> {
            SourceDocument source = invocation.getArgument(1);
            ChunkDraft draft = new ChunkDraft(source.documentId(), 0, List.of("Title"), 1, 2,
                    source.sourceHash(), "# Title\nDocument content", "Title\n# Title");
            return new IndexCandidate(KB, IndexVersion.BODY_V1, "bge-m3", "BAAI/bge-m3", 1024,
                    List.of(new SourceRef(source.documentId(), source.sourceHash())), List.of(draft), null,
                    null, "source-set");
        });
        when(retrievalIndexService.embedCandidate(any())).thenAnswer(invocation -> {
            IndexCandidate candidate = invocation.getArgument(0);
            return candidate == null ? null : candidate.withEmbeddings(List.of(new float[1024]));
        });

        DocumentFacadeServiceImpl target = new DocumentFacadeServiceImpl(documents,
                new DocumentConverter(new ObjectMapper()), storage, retrievalIndexService, legacyTitleIndexer,
                mock(StorageCleanupService.class), ingestionService, new IngestionSettings());
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (DocumentFacadeService) factory.getProxy();
    }

    @Test
    @DisplayName("an accepted upload commits document + job and does not call the embedding service")
    void acceptedUploadCommitsDocumentAndJobWithoutIndexing() throws Exception {
        CreateDocumentResponse response = service.uploadDocument(KB, upload, "actor-1");

        assertEquals(DOCUMENT, response.getDocumentId());
        assertEquals(JOB, response.getJobId());
        assertEquals("QUEUED", response.getIndexStatus());
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        verify(ingestionService).submit(eq(KB), eq(DOCUMENT), eq("kb/document/upload.md"), anyString(), eq("actor-1"));
        verify(retrievalIndexService, never()).embedCandidate(any());
        verify(retrievalIndexService, never()).publish(any());
        verify(storage, never()).deleteFile(any());
    }

    @Test
    @DisplayName("a failing job insert rolls the upload back and removes the saved file")
    void failedJobInsertRollsBackAndRemovesSavedFile() throws Exception {
        when(ingestionService.submit(any(), any(), any(), any(), any()))
                .thenThrow(new BizException("登记导入任务失败"));

        assertThrows(BizException.class, () -> service.uploadDocument(KB, upload, "actor-1"));

        assertEquals(1, transactions.rollbacks);
        assertEquals(0, transactions.commits);
        verify(storage).deleteFile("kb/document/upload.md");
    }

    @Test
    @DisplayName("a failed commit also removes the saved file")
    void failedCommitAlsoRemovesSavedFile() throws Exception {
        transactions.failCommit = true;

        assertThrows(TransactionSystemException.class, () -> service.uploadDocument(KB, upload, "actor-1"));

        verify(storage).deleteFile("kb/document/upload.md");
    }

    @Test
    @DisplayName("a non-markdown upload is rejected before any database or file write")
    void nonMarkdownUploadIsRejected() throws Exception {
        MockMultipartFile text = new MockMultipartFile("file", "notes.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        assertThrows(BizException.class, () -> service.uploadDocument(KB, text, "actor-1"));

        verifyNoInteractions(ingestionService);
        verify(storage, never()).saveFile(any(), any(), any());
        verify(documents, never()).insert(any());
    }

    @Test
    @DisplayName("the compatibility constructor keeps the task 03 synchronous publish path")
    void compatibilityConstructorStillPublishesSynchronously() {
        DocumentFacadeServiceImpl target = new DocumentFacadeServiceImpl(documents,
                new DocumentConverter(new ObjectMapper()), storage, retrievalIndexService, legacyTitleIndexer,
                mock(StorageCleanupService.class));
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        DocumentFacadeService synchronous = (DocumentFacadeService) factory.getProxy();

        CreateDocumentResponse response = synchronous.uploadDocument(KB, upload);

        assertEquals(DOCUMENT, response.getDocumentId());
        assertEquals("SUCCEEDED", response.getIndexStatus());
        assertTrue(response.getJobId() == null);
        verify(retrievalIndexService).publish(any());
        verifyNoInteractions(ingestionService);
    }

    @Test
    @DisplayName("an unreadable saved file rolls the submission back instead of accepting a job")
    void unreadableFileIsNotAccepted() throws Exception {
        Files.delete(savedFile);

        assertThrows(BizException.class, () -> service.uploadDocument(KB, upload, "actor-1"));

        assertEquals(1, transactions.rollbacks);
        verify(ingestionService, never()).submit(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the source hash stored with the job is the normalised text hash")
    void sourceHashIsNormalisedTextHash() throws Exception {
        byte[] crlf = "# Title\r\nDocument content".replace("\\r\\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        Files.write(savedFile, crlf);
        upload = new MockMultipartFile("file", "upload.md", "text/markdown", crlf);

        service.uploadDocument(KB, upload, "actor-1");

        String expected = com.kama.jchatmind.retrieval.SourceText
                .normalize("# Title\nDocument content".getBytes(StandardCharsets.UTF_8)).sha256();
        verify(ingestionService).submit(eq(KB), eq(DOCUMENT), eq("kb/document/upload.md"), eq(expected), eq("actor-1"));
        assertNotNull(expected);
    }

    private static class TrackingTransactions extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        boolean failCommit;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new TransactionSystemException("simulated commit failure");
            }
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
