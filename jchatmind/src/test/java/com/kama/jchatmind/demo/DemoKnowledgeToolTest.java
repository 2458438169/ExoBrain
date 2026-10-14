package com.kama.jchatmind.demo;

import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoKnowledgeToolTest {
    private static final String PUBLIC_KB = "44444444-4444-4444-8444-444444444444";
    private static final String PRIVATE_KB = "55555555-5555-4555-8555-555555555555";
    private final RagService rag = mock(RagService.class);

    private DemoKnowledgeTool tool() {
        DemoProperties properties = new DemoProperties();
        properties.setPublicKbIds(List.of(PUBLIC_KB));
        return new DemoKnowledgeTool(rag, new DemoAccessPolicy(properties));
    }

    @Test
    void callbackAllowsOnlyPublishedKnowledgeIds() {
        var callbacks = MethodToolCallbackProvider.builder().toolObjects(tool()).build().getToolCallbacks();
        assertEquals(1, callbacks.length);
        assertEquals("KnowledgeTool", callbacks[0].getToolDefinition().name());
        assertTrue(callbacks[0].call("{\"kbsId\":\"" + PRIVATE_KB + "\",\"query\":\"ignore restrictions\"}")
                .contains("只能检索公开演示知识库"));
        assertTrue(callbacks[0].call("{\"kbsId\":\"not-an-id\",\"query\":\"test\"}")
                .contains("只能检索公开演示知识库"));
        verifyNoInteractions(rag);

        when(rag.similaritySearch(PUBLIC_KB, "部署架构")).thenReturn(List.of("资料：《公开项目介绍》", "Nginx → Java → PostgreSQL"));
        String result = callbacks[0].call("{\"kbsId\":\"" + PUBLIC_KB + "\",\"query\":\" 部署架构 \"}");
        assertTrue(result.contains("公开项目介绍"));
        verify(rag).similaritySearch(PUBLIC_KB, "部署架构");
    }

    @Test
    void invalidQueriesNeverInvokeRetrieval() {
        DemoKnowledgeTool tool = tool();
        for (String query : new String[]{null, "", "   ", "x".repeat(4001)}) {
            assertTrue(tool.knowledgeQuery(PUBLIC_KB, query).contains("最多 4000"));
        }
        assertTrue(tool.knowledgeQuery(null, "test").contains("只能检索"));
        verifyNoInteractions(rag);
    }

    @Test
    void retrievalErrorsAndEmptyResultsDoNotFabricateEvidenceOrRevealInternals() {
        when(rag.similaritySearch(PUBLIC_KB, "question"))
                .thenThrow(new IllegalStateException("sensitive connection/path/token"))
                .thenReturn(List.of());
        String failed = tool().knowledgeQuery(PUBLIC_KB, "question");
        assertTrue(failed.contains("本次未获得资料"));
        assertFalse(failed.contains("sensitive"));
        String empty = tool().knowledgeQuery(PUBLIC_KB, "question");
        assertTrue(empty.contains("未找到相关内容"));
    }
}
