package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService.MarkdownSection;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.StorageCleanupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 并发隔离回归：同一个解析服务实例被多个请求同时使用。
 *
 * <p>每份合成文档在标题、正文、表格与代码块中都带有自己的唯一标记，断言只检查“结果里出现了谁的标记”，
 * 而不是章节数量。并发起跑由 {@link CyclicBarrier} 控制，所有等待都有超时上限；
 * 失败信息包含合成样本编号与轮次。</p>
 */
class MarkdownParserConcurrencyTest {

    private static final int DOCUMENTS = 16;
    private static final int ROUNDS = 5;
    private static final String KB_ID = "kb-int-check";
    private static final String CHINESE = "\u6b63\u6587\u5185\u5bb9";

    @Test
    void oneServiceInstanceParsesSixteenDocumentsConcurrentlyWithoutCrossingText() throws Exception {
        MarkdownParserServiceImpl service = new MarkdownParserServiceImpl();
        List<Sample> samples = syntheticDocuments();
        ExecutorService workers = daemonPool(DOCUMENTS, "markdown-parser-check");

        int parsedCalls = 0;
        long startedAt = System.nanoTime();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                CyclicBarrier startLine = new CyclicBarrier(DOCUMENTS);
                List<Future<List<MarkdownSection>>> parses = new ArrayList<>();
                for (Sample sample : samples) {
                    parses.add(workers.submit(() -> {
                        startLine.await(30, TimeUnit.SECONDS);
                        return service.parseMarkdown(utf8(sample.markdown()));
                    }));
                }
                for (int index = 0; index < samples.size(); index++) {
                    Sample sample = samples.get(index);
                    List<MarkdownSection> sections = parses.get(index).get(60, TimeUnit.SECONDS);
                    assertOnlyOwnText(round, sample, sections, samples);
                    parsedCalls++;
                }
            }
        } finally {
            workers.shutdownNow();
        }

        System.out.printf("concurrent parse isolation: %d documents x %d rounds = %d calls in %d ms%n",
                DOCUMENTS, ROUNDS, parsedCalls, (System.nanoTime() - startedAt) / 1_000_000);
    }

    /**
     * 真实解析器接入上传路径的双文档集成检查：真实 {@link MarkdownParserServiceImpl} +
     * 真实 {@link DocumentFacadeServiceImpl}，数据库与文件存储使用 mock 与临时目录，不连接外部环境。
     */
    @Test
    void twoDocumentsUploadedThroughTheRealParserKeepTheirOwnMetadataAndChunks(@TempDir Path storageRoot)
            throws Exception {
        MarkdownParserServiceImpl parser = new MarkdownParserServiceImpl();
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentStorageService storage = mock(DocumentStorageService.class);
        RagService rag = mock(RagService.class);
        ChunkBgeM3Mapper chunkMapper = mock(ChunkBgeM3Mapper.class);
        StorageCleanupService cleanup = mock(StorageCleanupService.class);

        String alphaMarkdown = "# @@ALPHA-TITLE@@\n\n@@ALPHA-BODY@@ " + CHINESE
                + "\n\n| col | val |\n| --- | --- |\n| @@ALPHA-TABLE@@ | x |\n\n```java\n// @@ALPHA-CODE@@\n```\n";
        String betaMarkdown = "# @@BETA-TITLE@@\n\n@@BETA-BODY@@ " + CHINESE + "\n";
        List<String> alphaContentMarkers = List.of("@@ALPHA-BODY@@", "@@ALPHA-TABLE@@", "@@ALPHA-CODE@@");
        List<String> betaContentMarkers = List.of("@@BETA-BODY@@");
        List<String> alphaAllMarkers = List.of("@@ALPHA-TITLE@@", "@@ALPHA-BODY@@", "@@ALPHA-TABLE@@", "@@ALPHA-CODE@@");
        List<String> betaAllMarkers = List.of("@@BETA-TITLE@@", "@@BETA-BODY@@");

        AtomicInteger generatedIds = new AtomicInteger();
        Map<String, String> documentIdByFilename = new ConcurrentHashMap<>();
        List<Document> updatedDocuments = Collections.synchronizedList(new ArrayList<>());
        List<ChunkBgeM3> insertedChunks = Collections.synchronizedList(new ArrayList<>());
        List<String> embeddedTitles = Collections.synchronizedList(new ArrayList<>());

        when(documentMapper.insert(any())).thenAnswer(invocation -> {
            Document inserted = invocation.getArgument(0);
            inserted.setId("doc-" + generatedIds.incrementAndGet());
            documentIdByFilename.put(inserted.getFilename(), inserted.getId());
            return 1;
        });
        when(documentMapper.updateById(any())).thenAnswer(invocation -> {
            updatedDocuments.add(invocation.getArgument(0));
            return 1;
        });
        when(storage.saveFile(anyString(), anyString(), any(MultipartFile.class))).thenAnswer(invocation -> {
            String kbId = invocation.getArgument(0, String.class);
            String documentId = invocation.getArgument(1, String.class);
            MultipartFile upload = invocation.getArgument(2, MultipartFile.class);
            String relativePath = kbId + "/" + documentId + "/" + upload.getOriginalFilename();
            Path target = storageRoot.resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.write(target, upload.getBytes());
            return relativePath;
        });
        when(storage.getFilePath(anyString()))
                .thenAnswer(invocation -> storageRoot.resolve(invocation.getArgument(0, String.class)));
        when(rag.embed(anyString(), anyString())).thenAnswer(invocation -> {
            embeddedTitles.add(invocation.getArgument(1, String.class));
            return new float[1024];
        });
        when(chunkMapper.insert(any())).thenAnswer(invocation -> {
            insertedChunks.add(invocation.getArgument(0));
            return 1;
        });

        DocumentFacadeServiceImpl facade = new DocumentFacadeServiceImpl(documentMapper,
                new DocumentConverter(new ObjectMapper()), storage, parser, rag, chunkMapper, cleanup);

        MockMultipartFile alphaUpload = new MockMultipartFile("file", "alpha.md", "text/markdown",
                alphaMarkdown.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile betaUpload = new MockMultipartFile("file", "beta.md", "text/markdown",
                betaMarkdown.getBytes(StandardCharsets.UTF_8));

        CreateDocumentResponse alphaResponse;
        CreateDocumentResponse betaResponse;
        ExecutorService workers = daemonPool(2, "markdown-upload-check");
        try {
            CyclicBarrier startLine = new CyclicBarrier(2);
            Future<CreateDocumentResponse> alpha = workers.submit(() -> {
                startLine.await(30, TimeUnit.SECONDS);
                return facade.uploadDocument(KB_ID, alphaUpload);
            });
            Future<CreateDocumentResponse> beta = workers.submit(() -> {
                startLine.await(30, TimeUnit.SECONDS);
                return facade.uploadDocument(KB_ID, betaUpload);
            });
            alphaResponse = alpha.get(60, TimeUnit.SECONDS);
            betaResponse = beta.get(60, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        String alphaId = alphaResponse.getDocumentId();
        String betaId = betaResponse.getDocumentId();
        assertNotEquals(alphaId, betaId, "both uploads must get their own document id");
        assertEquals(documentIdByFilename.get("alpha.md"), alphaId);
        assertEquals(documentIdByFilename.get("beta.md"), betaId);

        // 逐份核对知识库与文档记录
        assertEquals(2, updatedDocuments.size(), () -> "unexpected documents: " + updatedDocuments);
        for (Document stored : updatedDocuments) {
            assertEquals(KB_ID, stored.getKbId(), "document must belong to the uploaded knowledge base");
            assertEquals("md", stored.getFiletype());
            String expectedPath = KB_ID + "/" + stored.getId() + "/" + stored.getFilename();
            JsonNode metadata = new ObjectMapper().readTree(stored.getMetadata());
            assertNotNull(metadata.get("filePath"), () -> "missing filePath metadata: " + stored.getMetadata());
            assertEquals(expectedPath, metadata.get("filePath").asText(),
                    "document metadata must point at its own uploaded file");
            assertTrue(Files.exists(storageRoot.resolve(expectedPath)),
                    () -> "uploaded file is missing: " + expectedPath);
        }

        // 逐份核对 chunk 内容
        Map<String, List<ChunkBgeM3>> chunksByDocument = new HashMap<>();
        for (ChunkBgeM3 chunk : insertedChunks) {
            chunksByDocument.computeIfAbsent(chunk.getDocId(), key -> new ArrayList<>()).add(chunk);
        }
        assertEquals(Set.of(alphaId, betaId), chunksByDocument.keySet(),
                () -> "chunks must be split across both documents: " + chunksByDocument);
        assertChunksBelongTo(chunksByDocument.get(alphaId), alphaId, alphaContentMarkers, betaAllMarkers);
        assertChunksBelongTo(chunksByDocument.get(betaId), betaId, betaContentMarkers, alphaAllMarkers);

        // 逐份核对送入 embedding 的章节标题
        assertEquals(2, embeddedTitles.size(), () -> "unexpected titles: " + embeddedTitles);
        assertEquals(Set.of("@@ALPHA-TITLE@@", "@@BETA-TITLE@@"), new LinkedHashSet<>(embeddedTitles),
                () -> "titles from both uploads must stay separated: " + embeddedTitles);
    }

    private static void assertOnlyOwnText(int round, Sample sample, List<MarkdownSection> sections,
            List<Sample> allSamples) {
        String context = "round " + round + ", synthetic document " + sample.index();
        assertEquals(sample.titles().size(), sections.size(), context + ": unexpected section count");
        assertEquals(sample.titles(), sections.stream().map(MarkdownSection::getTitle).toList(),
                context + ": titles must belong to this document only");

        StringBuilder text = new StringBuilder();
        for (MarkdownSection section : sections) {
            text.append(section.getTitle()).append('\n').append(section.getContent()).append('\n');
        }
        String parsed = text.toString();

        for (String marker : sample.markers()) {
            assertTrue(parsed.contains(marker), context + ": missing own marker " + marker);
        }
        for (Sample other : allSamples) {
            if (other.index() == sample.index()) {
                continue;
            }
            for (String marker : other.markers()) {
                assertFalse(parsed.contains(marker),
                        context + ": text from synthetic document " + other.index() + " leaked into this result");
            }
        }
    }

    private static void assertChunksBelongTo(List<ChunkBgeM3> chunks, String documentId, List<String> ownMarkers,
            List<String> foreignMarkers) {
        assertNotNull(chunks, () -> "no chunks were written for document " + documentId);
        assertEquals(1, chunks.size(), () -> "unexpected chunk count for document " + documentId);
        for (ChunkBgeM3 chunk : chunks) {
            assertEquals(KB_ID, chunk.getKbId(), "chunk must belong to the uploaded knowledge base");
            assertEquals(documentId, chunk.getDocId(), "chunk must belong to its own document");
            assertEquals(1024, chunk.getEmbedding().length, "chunk must keep the embedding of its own title");
            for (String marker : ownMarkers) {
                assertTrue(chunk.getContent().contains(marker),
                        () -> "chunk of " + documentId + " is missing " + marker + ": " + chunk.getContent());
            }
            for (String marker : foreignMarkers) {
                assertFalse(chunk.getContent().contains(marker),
                        () -> "chunk of " + documentId + " contains foreign text " + marker + ": " + chunk.getContent());
            }
        }
    }

    private static List<Sample> syntheticDocuments() {
        List<Sample> samples = new ArrayList<>();
        for (int index = 0; index < DOCUMENTS; index++) {
            samples.add(syntheticDocument(index));
        }
        return samples;
    }

    private static Sample syntheticDocument(int index) {
        String id = String.format("M%02d", index);
        if (index == DOCUMENTS - 1) {
            return new Sample(index, "", List.of(), List.of());
        }
        String newline = index == 10 ? "\r\n" : "\n";
        String titleMarker = "@@" + id + "-TITLE@@";
        String bodyMarker = "@@" + id + "-BODY@@";
        String tableMarker = "@@" + id + "-TABLE@@";
        String codeMarker = "@@" + id + "-CODE@@";
        String duplicateMarker = "@@" + id + "-DUP@@";
        String duplicateBodyMarker = "@@" + id + "-DUPBODY@@";
        boolean duplicatedHeading = index % 4 == 0;

        List<String> titles = new ArrayList<>(List.of(titleMarker));
        List<String> markers = new ArrayList<>(List.of(titleMarker, bodyMarker, tableMarker, codeMarker));

        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(titleMarker).append(newline).append(newline);
        int fillerLines = switch (index % 3) {
            case 0 -> 40;
            case 1 -> 1;
            default -> 8;
        };
        for (int line = 0; line < fillerLines; line++) {
            markdown.append("filler ").append(id).append('-').append(line).append(' ').append(CHINESE)
                    .append(newline).append(newline);
        }
        markdown.append(bodyMarker).append(' ').append(CHINESE).append(newline).append(newline)
                .append("| col | val |").append(newline)
                .append("| --- | --- |").append(newline)
                .append("| ").append(tableMarker).append(" | x |").append(newline).append(newline)
                .append("```java").append(newline)
                .append("// ").append(codeMarker).append(newline)
                .append("```").append(newline);
        if (duplicatedHeading) {
            titles.add(duplicateMarker);
            markers.add(duplicateMarker);
            markers.add(duplicateBodyMarker);
            markdown.append(newline).append("## ").append(duplicateMarker).append(newline).append(newline)
                    .append(duplicateBodyMarker).append(newline);
        }
        return new Sample(index, markdown.toString(), List.copyOf(titles), List.copyOf(markers));
    }

    private static ExecutorService daemonPool(int threads, String threadName) {
        return Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static InputStream utf8(String markdown) {
        return new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8));
    }

    /** 一份合成文档：markdown 正文、期望的标题、以及仅属于它自己的唯一标记。 */
    private record Sample(int index, String markdown, List<String> titles, List<String> markers) {
    }
}
