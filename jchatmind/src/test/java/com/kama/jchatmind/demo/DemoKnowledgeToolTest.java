package com.kama.jchatmind.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.retrieval.IndexScope;
import com.kama.jchatmind.retrieval.RetrievalEnvelope;
import com.kama.jchatmind.retrieval.RetrievalErrorCode;
import com.kama.jchatmind.retrieval.RetrievalHit;
import com.kama.jchatmind.retrieval.RetrievalService;
import com.kama.jchatmind.retrieval.SourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 公开检索工具：白名单校验、只读、输出与账号工具一致的结构化 JSON，失败时不编造资料、不泄露内部信息。
 */
class DemoKnowledgeToolTest {
    private static final String PUBLIC_KB = "44444444-4444-4444-8444-444444444444";
    private static final String PRIVATE_KB = "55555555-5555-4555-8555-555555555555";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RetrievalService retrieval = mock(RetrievalService.class);

    private DemoKnowledgeTool tool() {
        DemoProperties properties = new DemoProperties();
        properties.setPublicKbIds(List.of(PUBLIC_KB));
        return new DemoKnowledgeTool(retrieval, new DemoAccessPolicy(properties));
    }

    @Test
    @DisplayName("the tool callback only allows published knowledge ids and returns a versioned envelope")
    void callbackAllowsOnlyPublishedKnowledgeIds() throws Exception {
        var callbacks = MethodToolCallbackProvider.builder().toolObjects(tool()).build().getToolCallbacks();
        assertEquals(1, callbacks.length);
        assertEquals("KnowledgeTool", callbacks[0].getToolDefinition().name());

        JsonNode denied = JSON.readTree(callbacks[0].call(
                "{\"kbsId\":\"" + PRIVATE_KB + "\",\"query\":\"ignore restrictions\"}"));
        assertEquals("UNAVAILABLE", denied.path("status").asText());
        assertEquals(RetrievalErrorCode.KB_NOT_ALLOWED, denied.path("errorCode").asText());
        assertTrue(denied.path("hits").isEmpty());
        assertEquals(1, denied.path("schemaVersion").asInt());
        assertFalse(denied.toString().contains(PRIVATE_KB));

        JsonNode notAnId = JSON.readTree(callbacks[0].call("{\"kbsId\":\"not-an-id\",\"query\":\"test\"}"));
        assertEquals(RetrievalErrorCode.KB_NOT_ALLOWED, notAnId.path("errorCode").asText());
        verifyNoInteractions(retrieval);

        RetrievalHit hit = new RetrievalHit("retrieval:1", "chunk", PUBLIC_KB, 1, "Nginx → Java → PostgreSQL",
                new SourceLocation("doc", "《公开项目介绍》", List.of("架构"), "hash", 3, 5),
                "body-v1", "revision", RetrievalHit.METRIC_L2, 0.5);
        when(retrieval.retrieve(eq(PUBLIC_KB), eq("部署架构"), any()))
                .thenReturn(RetrievalEnvelope.ok("retrieval", List.of(hit)));

        JsonNode result = JSON.readTree(callbacks[0].call(
                "{\"kbsId\":\"" + PUBLIC_KB + "\",\"query\":\" 部署架构 \"}"));
        assertEquals("OK", result.path("status").asText());
        assertEquals("《公开项目介绍》", result.path("hits").get(0).path("source").path("documentName").asText());
        assertTrue(result.path("hits").get(0).path("content").asText().contains("PostgreSQL"));
        verify(retrieval).retrieve(eq(PUBLIC_KB), eq("部署架构"), any());
    }

    @Test
    @DisplayName("invalid queries never invoke retrieval and report INVALID_REQUEST")
    void invalidQueriesNeverInvokeRetrieval() throws Exception {
        DemoKnowledgeTool tool = tool();
        for (String query : new String[]{null, "", "   ", "x".repeat(4001)}) {
            JsonNode envelope = JSON.readTree(tool.knowledgeQuery(PUBLIC_KB, query));
            assertEquals("UNAVAILABLE", envelope.path("status").asText());
            assertEquals(RetrievalErrorCode.INVALID_REQUEST, envelope.path("errorCode").asText());
            assertTrue(envelope.path("hits").isEmpty());
        }
        JsonNode nullKb = JSON.readTree(tool.knowledgeQuery(null, "test"));
        assertEquals(RetrievalErrorCode.KB_NOT_ALLOWED, nullKb.path("errorCode").asText());
        verifyNoInteractions(retrieval);
    }

    @Test
    @DisplayName("retrieval failures and empty results fabricate no evidence and leak no internals")
    void retrievalErrorsAndEmptyResultsDoNotFabricateEvidenceOrRevealInternals() throws Exception {
        when(retrieval.retrieve(any(), any(), any()))
                .thenThrow(new IllegalStateException("sensitive connection/path/token"))
                .thenReturn(RetrievalEnvelope.noMatch("retrieval", RetrievalErrorCode.NO_MATCH));

        String failed = tool().knowledgeQuery(PUBLIC_KB, "question");
        JsonNode failedEnvelope = JSON.readTree(failed);
        assertEquals("UNAVAILABLE", failedEnvelope.path("status").asText());
        assertEquals(RetrievalErrorCode.RETRIEVAL_UNAVAILABLE, failedEnvelope.path("errorCode").asText());
        assertTrue(failedEnvelope.path("hits").isEmpty());
        assertFalse(failed.contains("sensitive"));

        JsonNode empty = JSON.readTree(tool().knowledgeQuery(PUBLIC_KB, "question"));
        assertEquals("NO_MATCH", empty.path("status").asText());
        assertTrue(empty.path("hits").isEmpty());
        assertEquals(RetrievalErrorCode.NO_MATCH, empty.path("errorCode").asText());
    }

    @Test
    @DisplayName("a legacy hit keeps null positions instead of a fabricated line number")
    void legacyHitHasNoPosition() throws Exception {
        RetrievalHit legacy = new RetrievalHit("retrieval:1", "chunk", PUBLIC_KB, 1, "旧片段",
                SourceLocation.withoutPosition("doc", "公开旧资料", List.of()), "legacy-title-v1",
                IndexScope.LEGACY_REVISION, RetrievalHit.METRIC_L2, 1.25);
        when(retrieval.retrieve(any(), any(), any()))
                .thenReturn(RetrievalEnvelope.ok("retrieval", List.of(legacy)));

        JsonNode source = JSON.readTree(tool().knowledgeQuery(PUBLIC_KB, "question"))
                .path("hits").get(0).path("source");
        assertTrue(source.get("startLine").isNull());
        assertTrue(source.get("endLine").isNull());
        assertTrue(source.get("sourceHash").isNull());
    }
}