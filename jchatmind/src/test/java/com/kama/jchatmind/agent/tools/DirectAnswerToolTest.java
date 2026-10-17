package com.kama.jchatmind.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * directAnswer 的兼容性：citationIds 是可选的，只有 answer 的旧调用仍然可以完成普通答复；
 * 工具本身不消费答复，也不持有任何运行状态（引用由 JChatMind 校验并补全）。
 */
class DirectAnswerToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolCallback callback() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new DirectAnswerTool())
                .build()
                .getToolCallbacks();
        assertEquals(1, callbacks.length);
        return callbacks[0];
    }

    @Test
    @DisplayName("the tool schema advertises citationIds as an optional argument")
    void citationIdsIsOptionalInTheGeneratedSchema() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schema = mapper.readTree(callback().getToolDefinition().inputSchema());
        assertTrue(schema.path("properties").has("answer"));
        assertTrue(schema.path("properties").has("citationIds"), schema.toString());
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(node -> required.add(node.asText()));
        assertTrue(required.contains("answer"), schema.toString());
        assertFalse(required.contains("citationIds"), "citationIds must stay optional: " + schema);
    }

    @Test
    @DisplayName("an old call with only answer still completes, and citationIds is accepted when present")
    void oldCallWithOnlyAnswerStillCompletes() throws Exception {
        // 工具返回值经 Spring AI 的默认结果转换器编码：JSON 形状的字符串保持原样，普通文本变成 JSON 字符串字面量。
        assertEquals("\"答复已交付\"", callback().call("{\"answer\":\"只有回答\"}"));
        assertEquals("\"答复已交付\"", callback().call("{\"answer\":\"带引用\",\"citationIds\":[\"ret-1:1\"]}"));
        assertEquals("\"答复已交付\"", callback().call("{\"answer\":\"空引用\",\"citationIds\":[]}"));
        JsonNode withIds = JSON.readTree("{\"citationIds\":[\"ret-1:1\"]}");
        assertEquals("ret-1:1", withIds.path("citationIds").get(0).asText());
    }
}
