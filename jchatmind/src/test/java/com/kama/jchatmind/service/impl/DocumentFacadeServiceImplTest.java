package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentFacadeServiceImplTest {

    @TempDir
    Path temporaryDirectory;

    private final DocumentMapper documents = mock(DocumentMapper.class);
    private final DocumentStorageService storage = mock(DocumentStorageService.class);
    private final MarkdownParserService parser = mock(MarkdownParserService.class);
    private final RagService rag = mock(RagService.class);
    private final ChunkBgeM3Mapper chunks = mock(ChunkBgeM3Mapper.class);
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
            document.setId("document");
            return 1;
        });
        when(documents.updateById(any())).thenReturn(1);
        when(storage.saveFile(eq("kb"), eq("document"), any())).thenReturn("kb/document/upload.md");
        when(storage.getFilePath("kb/document/upload.md")).thenReturn(savedFile);
        when(parser.parseMarkdown(any())).thenReturn(List.of(
                new MarkdownParserService.MarkdownSection("Title", "Document content")));
        when(rag.embed("kb", "Title")).thenReturn(new float[1024]);
        when(chunks.insert(any())).thenReturn(1);

        DocumentFacadeServiceImpl target = new DocumentFacadeServiceImpl(documents,
                new DocumentConverter(new ObjectMapper()), storage, parser, rag, chunks,
                mock(com.kama.jchatmind.service.StorageCleanupService.class));
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (DocumentFacadeService) factory.getProxy();
    }

    @Test
    void failedEmbeddingRollsBackUploadAndRemovesSavedFile() throws Exception {
        when(rag.embed("kb", "Title")).thenThrow(new IllegalStateException("embedding service unavailable"));

        assertThrows(BizException.class, () -> service.uploadDocument("kb", upload));

        assertEquals(1, transactions.rollbacks);
        assertEquals(0, transactions.commits);
        verify(storage).deleteFile("kb/document/upload.md");
        verify(chunks, never()).insert(any());
    }

    @Test
    void successfulIndexingCommitsAndKeepsSavedFile() throws Exception {
        assertEquals("document", service.uploadDocument("kb", upload).getDocumentId());

        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        verify(chunks).insert(any());
        verify(storage, never()).deleteFile(any());
    }

    @Test
    void failedCommitAlsoRemovesSavedFile() throws Exception {
        transactions.failCommit = true;

        assertThrows(TransactionSystemException.class, () -> service.uploadDocument("kb", upload));

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
