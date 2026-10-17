package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.entity.Document;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 上传路径：候选 → 校验 → 原子发布，失败补偿删除已保存文件，legacy 库不静默切换。 */
class DocumentFacadeServiceImplTest {

    private static final String KB = "11111111-1111-4111-8111-111111111111";
    private static final String DOCUMENT = "document";

    @TempDir
    Path temporaryDirectory;

    private final DocumentMapper documents = mock(DocumentMapper.class);
    private final DocumentStorageService storage = mock(DocumentStorageService.class);
    private final RetrievalIndexService retrievalIndexService = mock(RetrievalIndexService.class);
    private final LegacyTitleIndexer legacyTitleIndexer = mock(LegacyTitleIndexer.class);
    private final TrackingTransactions transactions = new TrackingTransactions();
    private DocumentFacadeService service;
    private MockMultipartFile upload;

    @BeforeEach
    void setUp() throws Exception {
        byte[] contents = "# Title\nDocument content".getBytes(StandardCharsets.UTF_8);
        Path savedFile = temporaryDirectory.resolve("upload.md");
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
        when(retrievalIndexService.resolveScope(KB)).thenReturn(new IndexScope(IndexVersion.BODY_V1, null));
        when(retrievalIndexService.prepareDocument(eq(KB), any())).thenAnswer(invocation -> {
            SourceDocument source = invocation.getArgument(1);
            ChunkDraft draft = new ChunkDraft(source.documentId(), 0, List.of("Title"), 1, 2,
                    source.sourceHash(), "# Title\nDocument content", "Title\n# Title");
            return new IndexCandidate(KB, IndexVersion.BODY_V1, "bge-m3", "BAAI/bge-m3", 1024,
                    List.of(new SourceRef(source.documentId(), source.sourceHash())), List.of(draft), null,
                    null, "source-set");
        });
        // Mockito 在重新打桩时也会用占位参数调用已有 answer，因此要允许 null。
        when(retrievalIndexService.embedCandidate(any())).thenAnswer(invocation -> {
            IndexCandidate candidate = invocation.getArgument(0);
            return candidate == null ? null : candidate.withEmbeddings(List.of(new float[1024]));
        });

        DocumentFacadeServiceImpl target = new DocumentFacadeServiceImpl(documents,
                new DocumentConverter(new ObjectMapper()), storage, retrievalIndexService, legacyTitleIndexer,
                mock(StorageCleanupService.class));
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (DocumentFacadeService) factory.getProxy();
    }

    @Test
    @DisplayName("a failed embedding rolls the upload back and removes the saved file")
    void failedEmbeddingRollsBackUploadAndRemovesSavedFile() throws Exception {
        doThrow(new IllegalStateException("embedding service unavailable"))
                .when(retrievalIndexService).embedCandidate(any());

        assertThrows(BizException.class, () -> service.uploadDocument(KB, upload));

        assertEquals(1, transactions.rollbacks);
        assertEquals(0, transactions.commits);
        verify(storage).deleteFile("kb/document/upload.md");
        verify(retrievalIndexService, never()).publish(any());
    }

    @Test
    @DisplayName("a successful upload commits the published index and keeps the saved file")
    void successfulIndexingCommitsAndKeepsSavedFile() throws Exception {
        assertEquals(DOCUMENT, service.uploadDocument(KB, upload).getDocumentId());

        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        verify(retrievalIndexService).publish(any());
        verify(storage, never()).deleteFile(any());
    }

    @Test
    @DisplayName("a failed commit also removes the saved file")
    void failedCommitAlsoRemovesSavedFile() throws Exception {
        transactions.failCommit = true;

        assertThrows(TransactionSystemException.class, () -> service.uploadDocument(KB, upload));

        verify(storage).deleteFile("kb/document/upload.md");
    }

    @Test
    @DisplayName("a legacy knowledge base keeps writing legacy rows instead of silently switching strategy")
    void legacyKnowledgeBaseKeepsLegacyRows() throws Exception {
        when(retrievalIndexService.resolveScope(KB)).thenReturn(IndexScope.legacy());
        when(legacyTitleIndexer.index(eq(KB), eq(DOCUMENT), any())).thenReturn(2);

        assertEquals(DOCUMENT, service.uploadDocument(KB, upload).getDocumentId());

        verify(legacyTitleIndexer).index(eq(KB), eq(DOCUMENT), any());
        verify(retrievalIndexService, never()).prepareDocument(anyString(), any());
        verify(retrievalIndexService, never()).publish(any());
    }

    @Test
    @DisplayName("a document without any indexable section is rejected with the existing message")
    void emptyDocumentIsRejected() throws Exception {
        when(retrievalIndexService.prepareDocument(eq(KB), any())).thenThrow(new BizException(
                "Markdown 文档没有可索引的章节"));

        BizException error = assertThrows(BizException.class, () -> service.uploadDocument(KB, upload));

        assertEquals("Markdown 文档没有可索引的章节", error.getMessage());
        verify(storage).deleteFile("kb/document/upload.md");
    }

    private static class TrackingTransactions extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        boolean failCommit;

        @Override
        protected Object doGetTransaction() { return new Object(); }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) { }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new TransactionSystemException("simulated commit failure");
            }
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}