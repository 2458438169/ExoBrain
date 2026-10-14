package com.kama.jchatmind.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.demo.*;
import com.kama.jchatmind.exception.GlobalExceptionHandler;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.impl.DocumentFacadeServiceImpl;
import com.kama.jchatmind.service.impl.DocumentStorageServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentContentControllerTest {
    private static final String DOCUMENT = "abcdef12-2222-4222-8222-222222222222";
    private static final String PRIVATE_DOCUMENT = "33333333-3333-4333-8333-333333333333";
    private static final String MISSING_DOCUMENT = "66666666-6666-4666-8666-666666666666";
    private static final String PUBLIC_KB = "44444444-4444-4444-8444-444444444444";
    private static final String PRIVATE_KB = "55555555-5555-4555-8555-555555555555";

    @TempDir Path root;
    private final DocumentMapper documents = mock(DocumentMapper.class);
    private final MarkdownParserService parser = mock(MarkdownParserService.class);
    private final RagService rag = mock(RagService.class);
    private final ChunkBgeM3Mapper chunks = mock(ChunkBgeM3Mapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final DemoProperties properties = new DemoProperties();
    private DocumentStorageServiceImpl storage;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        storage = spy(new DocumentStorageServiceImpl());
        ReflectionTestUtils.setField(storage, "baseStoragePath", root.toString());
        var facade = new DocumentFacadeServiceImpl(documents, new DocumentConverter(json), storage, parser, rag, chunks);
        properties.setPublicKbIds(List.of(PUBLIC_KB));
        var catalog = new DemoCatalogService(new DemoAccessPolicy(properties), mock(DemoSessionRepository.class),
                mock(KnowledgeBaseMapper.class), documents, mock(ChatMessageFacadeService.class), json, facade);
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(facade),
                        new DemoController(mock(DemoChatService.class), new DemoVisitor(), catalog))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/demo"})
    void returnsTheFullOriginalMarkdownWithNoStorageMetadata(String prefix) throws Exception {
        String original = "# 中文标题 🚀\r\n\r\n正文中保留 \ufeff 字符。\n\n```java\nreturn 1;\n```\n";
        Document document = saved(DOCUMENT, PUBLIC_KB, "notes.MarkDown", "\ufeff" + original);
        document.setFilename("C:\\private\\upload\\notes.MarkDown");

        mvc.perform(get(prefix + "/documents/" + DOCUMENT.toUpperCase() + "/content")
                        .param("filePath", "../../private.md"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.documentId").value(DOCUMENT))
                .andExpect(jsonPath("$.data.filename").value("notes.MarkDown"))
                .andExpect(jsonPath("$.data.content").value(original))
                .andExpect(jsonPath("$.data.size()").value(3))
                .andExpect(content().string(not(containsString("filePath"))))
                .andExpect(content().string(not(containsString("private"))));
        verify(documents, times(1)).selectById(DOCUMENT);
        verifyNoInteractions(parser, rag, chunks);
    }

    @Test
    void publicPreviewRequiresPublicationBeforeAnyFileReadAndCannotBeSpoofedByQuery() throws Exception {
        Document document = Document.builder().id(PRIVATE_DOCUMENT).kbId(PRIVATE_KB)
                .filename("private.pdf").metadata("invalid secret metadata").build();
        when(documents.selectById(PRIVATE_DOCUMENT)).thenReturn(document);
        String privateBody = mvc.perform(get("/api/demo/documents/" + PRIVATE_DOCUMENT + "/content")
                        .param("kbId", PUBLIC_KB).param("filePath", "public.md"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("文档不存在"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String missingBody = mvc.perform(get("/api/demo/documents/" + MISSING_DOCUMENT + "/content"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(missingBody, privateBody);
        verifyNoInteractions(storage, parser, rag, chunks);
    }

    @Test
    void removingPublicationImmediatelyRevokesPreview() throws Exception {
        saved(DOCUMENT, PUBLIC_KB, "notes.md", "# published");
        mvc.perform(get("/api/demo/documents/" + DOCUMENT + "/content"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        clearInvocations(storage);
        properties.setPublicKbIds(List.of());
        mvc.perform(get("/api/demo/documents/" + DOCUMENT + "/content")).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }

    @Test
    void managementPreviewCanReadAStoredDocumentOutsideThePublicAllowlist() throws Exception {
        saved(PRIVATE_DOCUMENT, PRIVATE_KB, "notes.MD", "# management document");
        mvc.perform(get("/api/documents/" + PRIVATE_DOCUMENT + "/content"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value("# management document"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/demo"})
    void invalidDocumentIdsAreRejectedWithoutQueryingTheDatabase(String prefix) throws Exception {
        for (String id : new String[]{"not-a-uuid", "1-1-1-1-1"}) {
            mvc.perform(get(prefix + "/documents/" + id + "/content"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        }
        verifyNoInteractions(documents, storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/demo"})
    void unavailableOriginalAndMalformedMetadataProduceSafe404s(String prefix) throws Exception {
        Document document = saved(DOCUMENT, PUBLIC_KB, "notes.md", "# original");
        Files.delete(root.resolve(PUBLIC_KB).resolve(DOCUMENT).resolve("saved.md"));
        mvc.perform(get(prefix + "/documents/" + DOCUMENT + "/content"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("文档原文不可用"));
        for (String metadata : new String[]{null, "{}", "{\"filePath\":\"/private/secret.md\"}", "private broken metadata"}) {
            document.setMetadata(metadata);
            mvc.perform(get(prefix + "/documents/" + DOCUMENT + "/content"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404))
                    .andExpect(content().string(not(containsString("private"))))
                    .andExpect(content().string(not(containsString(root.toString()))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/demo"})
    void rejectsNonMarkdownAndOversizedOriginalsWithMatchingHttpAndApiCodes(String prefix) throws Exception {
        Document document = saved(DOCUMENT, PUBLIC_KB, "notes.txt", "not markdown");
        mvc.perform(get(prefix + "/documents/" + DOCUMENT + "/content"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(415));
        verifyNoInteractions(storage);
        document.setFilename("notes.md");
        // A small or stale database size must not bypass the actual file-size limit.
        document.setSize(1L);
        Files.write(root.resolve(PUBLIC_KB).resolve(DOCUMENT).resolve("saved.md"),
                new byte[DocumentStorageService.MAX_PREVIEW_BYTES + 1]);
        mvc.perform(get(prefix + "/documents/" + DOCUMENT + "/content"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(413));
    }

    @Test
    void publicPreviewCannotFollowMetadataIntoAnotherDocumentOrAcceptWrites() throws Exception {
        Document document = saved(DOCUMENT, PUBLIC_KB, "notes.md", "# public");
        Document privateDocument = saved(PRIVATE_DOCUMENT, PRIVATE_KB, "private.md", "private secret");
        document.setMetadata(privateDocument.getMetadata());
        mvc.perform(get("/api/demo/documents/" + DOCUMENT + "/content"))
                .andExpect(status().isNotFound()).andExpect(content().string(not(containsString("secret"))));
        mvc.perform(post("/api/demo/documents/" + DOCUMENT + "/content"))
                .andExpect(status().isForbidden());
    }

    private Document saved(String id, String kbId, String filename, String content) throws IOException {
        String relative = kbId + "/" + id + "/saved.md";
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Document document = Document.builder().id(id).kbId(kbId).filename(filename).filetype("md")
                .size(Files.size(file)).metadata(json.writeValueAsString(Map.of("filePath", relative))).build();
        when(documents.selectById(id)).thenReturn(document);
        return document;
    }
}
