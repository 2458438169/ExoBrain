package com.kama.jchatmind.retrieval;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.models.ModelDefinition;
import com.kama.jchatmind.models.ModelException;
import com.kama.jchatmind.models.ModelKind;
import com.kama.jchatmind.models.ModelRuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 检索服务的契约行为：授权顺序、状态区分、来源字段、版本过滤与安全错误码。 */
class RetrievalServiceImplTest {

    private static final String KB = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_KB = "99999999-9999-4999-8999-999999999999";
    private static final String REVISION = "22222222-2222-4222-8222-222222222222";
    private static final String DOCUMENT = "33333333-3333-4333-8333-333333333333";
    private static final String CHUNK = "44444444-4444-4444-8444-444444444444";

    private final ChunkBgeM3Mapper chunks = mock(ChunkBgeM3Mapper.class);
    private final ModelCatalogService catalog = mock(ModelCatalogService.class);
    private final ModelRuntimeService runtime = mock(ModelRuntimeService.class);
    private final RetrievalIndexService indexService = mock(RetrievalIndexService.class);
    private final RetrievalIndexRepository repository = mock(RetrievalIndexRepository.class);
    private final RetrievalSettings settings = new RetrievalSettings();
    private RetrievalService service;

    private final ModelDefinition model = new ModelDefinition("bge-m3", "bge-m3", ModelKind.EMBEDDING,
            "openai", "https://example.invalid", "BAAI/bge-m3", null, true, true, 1024,
            Instant.now(), Instant.now());

    @BeforeEach
    void setUp() {
        service = new RetrievalServiceImpl(chunks, catalog, runtime, indexService, repository, settings);
        when(catalog.embeddingForKnowledgeBase(KB)).thenReturn(model);
        when(runtime.embed(eq(model), anyString())).thenReturn(new float[1024]);
        when(indexService.resolveScope(KB)).thenReturn(new IndexScope(IndexVersion.BODY_V1, REVISION));
        when(repository.documentNames(any())).thenReturn(Map.of(DOCUMENT, "设计.md"));
    }

    @Test
    @DisplayName("an unauthorized knowledge base is an access failure, checked before model and vector work")
    void unauthorizedIsAnAccessFailure() {
        assertThrows(RetrievalAccessDeniedException.class,
                () -> service.retrieve(KB, "查询", kbId -> false));

        verifyNoInteractions(catalog, runtime, chunks, indexService, repository);
    }

    @Test
    @DisplayName("a blank or oversized query is never reported as a successful retrieval")
    void invalidQueriesAreUnavailable() {
        for (String query : new String[]{null, "", "   ", "x".repeat(4001)}) {
            RetrievalEnvelope envelope = service.retrieve(KB, query, kbId -> true);
            assertEquals(RetrievalStatus.UNAVAILABLE, envelope.status());
            assertEquals(RetrievalErrorCode.INVALID_REQUEST, envelope.errorCode());
            assertTrue(envelope.hits().isEmpty());
        }
        assertThrows(RetrievalAccessDeniedException.class,
                () -> service.retrieve(OTHER_KB, "查询", kbId -> false));
        verifyNoInteractions(chunks);
    }

    @Test
    @DisplayName("an index without any hit is NO_MATCH, and an unbuilt index is reported as such")
    void emptyResultIsNoMatch() {
        when(chunks.searchScoped(eq(KB), eq(REVISION), anyString(), anyInt())).thenReturn(List.of());
        RetrievalEnvelope envelope = service.retrieve(KB, "查询", kbId -> true);
        assertEquals(RetrievalStatus.NO_MATCH, envelope.status());
        assertEquals(RetrievalErrorCode.NO_MATCH, envelope.errorCode());
        assertTrue(envelope.hits().isEmpty());

        when(indexService.resolveScope(KB)).thenReturn(new IndexScope(IndexVersion.BODY_V1, null));
        when(chunks.searchScoped(eq(KB), eq(null), anyString(), anyInt())).thenReturn(List.of());
        RetrievalEnvelope unbuilt = service.retrieve(KB, "查询", kbId -> true);
        assertEquals(RetrievalStatus.NO_MATCH, unbuilt.status());
        assertEquals(RetrievalErrorCode.NO_INDEXED_CONTENT, unbuilt.errorCode());
    }

    @Test
    @DisplayName("OK hits carry rank, citationId, source location, index version/revision and L2 distance")
    void okHitsCarrySourceLocation() {
        when(chunks.searchScoped(eq(KB), eq(REVISION), anyString(), eq(3)))
                .thenReturn(List.of(bodyRow("b", 0.25, 5, 9), bodyRow("a", 1.5, 20, 24)));

        RetrievalEnvelope envelope = service.retrieve(KB, "  查询文本  ", kbId -> true);

        assertEquals(RetrievalStatus.OK, envelope.status());
        assertNull(envelope.errorCode());
        assertEquals(2, envelope.hits().size());
        RetrievalHit first = envelope.hits().get(0);
        assertEquals(1, first.rank());
        assertEquals(envelope.retrievalId() + ":1", first.citationId());
        assertEquals("body-v1", first.indexVersion());
        assertEquals(REVISION, first.indexRevision());
        assertEquals("l2", first.metric());
        assertEquals(0.25, first.distance(), 1e-9);
        assertEquals(DOCUMENT, first.source().documentId());
        assertEquals("设计.md", first.source().documentName());
        assertEquals(List.of("部署", "网络"), first.source().headingPath());
        assertEquals("hash-1", first.source().sourceHash());
        assertEquals(5, first.source().startLine());
        assertEquals(9, first.source().endLine());
        assertEquals(2, envelope.hits().get(1).rank());
        assertEquals(envelope.retrievalId() + ":2", envelope.hits().get(1).citationId());
        verify(runtime).embed(model, "查询文本");
        verify(chunks).searchScoped(eq(KB), eq(REVISION), anyString(), eq(3));
    }

    @Test
    @DisplayName("legacy rows keep null positions and never fabricate a line number")
    void legacyRowsHaveNoPosition() {
        when(indexService.resolveScope(KB)).thenReturn(IndexScope.legacy());
        ScoredChunk row = new ScoredChunk();
        row.setId(CHUNK);
        row.setKbId(KB);
        row.setDocId(DOCUMENT);
        row.setContent("旧标题向量命中的正文");
        row.setMetadata(null);
        row.setDistance(0.75);
        when(chunks.searchScoped(eq(KB), eq(null), anyString(), anyInt())).thenReturn(List.of(row));

        RetrievalEnvelope envelope = service.retrieve(KB, "查询", kbId -> true);

        assertEquals(RetrievalStatus.OK, envelope.status());
        RetrievalHit hit = envelope.hits().get(0);
        assertEquals("legacy-title-v1", hit.indexVersion());
        assertEquals(IndexScope.LEGACY_REVISION, hit.indexRevision());
        assertNull(hit.source().sourceHash());
        assertNull(hit.source().startLine());
        assertNull(hit.source().endLine());
        assertTrue(hit.source().headingPath().isEmpty());
        assertEquals("设计.md", hit.source().documentName());
    }

    @Test
    @DisplayName("a non-finite or negative distance is an index failure, not a fabricated number")
    void invalidDistanceIsUnavailable() {
        ScoredChunk row = bodyRow("x", 0.5, 1, 2);
        row.setDistance(Double.NaN);
        when(chunks.searchScoped(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of(row));

        RetrievalEnvelope envelope = service.retrieve(KB, "查询", kbId -> true);

        assertEquals(RetrievalStatus.UNAVAILABLE, envelope.status());
        assertEquals(RetrievalErrorCode.INDEX_INVALID, envelope.errorCode());
        assertTrue(envelope.hits().isEmpty());
    }

    @Test
    @DisplayName("model binding and embedding failures are UNAVAILABLE and never touch the vector query")
    void modelFailuresAreUnavailable() {
        when(catalog.embeddingForKnowledgeBase(KB)).thenThrow(new ModelException(409, "知识库尚未绑定向量模型"));
        RetrievalEnvelope unbound = service.retrieve(KB, "查询", kbId -> true);
        assertEquals(RetrievalStatus.UNAVAILABLE, unbound.status());
        assertEquals(RetrievalErrorCode.MODEL_BINDING_UNAVAILABLE, unbound.errorCode());
        verifyNoInteractions(chunks);

        doReturn(model).when(catalog).embeddingForKnowledgeBase(KB);
        doThrow(new ModelException(502, "向量服务暂不可用")).when(runtime).embed(eq(model), anyString());
        RetrievalEnvelope embeddingDown = service.retrieve(KB, "查询", kbId -> true);
        assertEquals(RetrievalStatus.UNAVAILABLE, embeddingDown.status());
        assertEquals(RetrievalErrorCode.EMBEDDING_UNAVAILABLE, embeddingDown.errorCode());
        assertTrue(embeddingDown.hits().isEmpty());
        verifyNoInteractions(chunks);
    }

    @Test
    @DisplayName("an unreadable index scope is UNAVAILABLE instead of an empty result")
    void unreadableScopeIsUnavailable() {
        when(indexService.resolveScope(KB)).thenThrow(new IllegalStateException("index metadata missing"));
        RetrievalEnvelope envelope = service.retrieve(KB, "查询", kbId -> true);
        assertEquals(RetrievalStatus.UNAVAILABLE, envelope.status());
        assertEquals(RetrievalErrorCode.INDEX_UNAVAILABLE, envelope.errorCode());
        verifyNoInteractions(chunks);
    }

    @Test
    @DisplayName("the optional distance threshold is off by default and calibatable by configuration")
    void distanceThresholdIsOptional() {
        when(chunks.searchScoped(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(bodyRow("weak", 42.0, 1, 4)));
        assertEquals(RetrievalStatus.OK, service.retrieve(KB, "查询", kbId -> true).status(),
                "no threshold is configured by default, so a hit is still returned with its real distance");

        settings.setMaxDistance(2.0);
        RetrievalEnvelope rejected = service.retrieve(KB, "查询", kbId -> true);
        assertEquals(RetrievalStatus.NO_MATCH, rejected.status());
        assertEquals(RetrievalErrorCode.NO_MATCH, rejected.errorCode());
        assertTrue(rejected.hits().isEmpty());
    }

    @Test
    @DisplayName("the transitional adapter rethrows the underlying failure before any vector query")
    void requireHitsRethrowsUnderlyingFailure() {
        when(runtime.embed(eq(model), anyString())).thenThrow(new ModelException(400, "当前知识库仅支持1024维向量模型"));

        ModelException error = assertThrows(ModelException.class,
                () -> service.requireHits(KB, "查询", kbId -> true));

        assertTrue(error.getMessage().contains("1024"));
        verifyNoInteractions(chunks);
    }

    private static ScoredChunk bodyRow(String id, double distance, int startLine, int endLine) {
        ScoredChunk row = new ScoredChunk();
        row.setId(CHUNK.equals(id) ? id : "55555555-5555-4555-8555-55555555555" + (id.equals("a") ? "5" : "6"));
        row.setKbId(KB);
        row.setDocId(DOCUMENT);
        row.setContent("正文片段 " + id);
        row.setMetadata(ChunkMetadata.encode(new ChunkDraft(DOCUMENT, 0, List.of("部署", "网络"), startLine,
                endLine, "hash-1", "正文片段 " + id, "部署 > 网络\n正文片段 " + id), IndexVersion.BODY_V1, "设计.md"));
        row.setIndexVersion("body-v1");
        row.setSourceHash("hash-1");
        row.setStartLine(startLine);
        row.setEndLine(endLine);
        row.setDistance(distance);
        return row;
    }
}