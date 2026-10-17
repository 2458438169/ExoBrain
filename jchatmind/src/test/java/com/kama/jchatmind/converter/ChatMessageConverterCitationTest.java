package com.kama.jchatmind.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.citation.AnswerCitation;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.retrieval.SourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消息 metadata 的增量字段：新记录带结构化引用，旧记录（只有 toolCalls/toolResponse）继续可读，
 * 未来新增的无关字段不会让旧读者失败。
 */
class ChatMessageConverterCitationTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final ChatMessageConverter converter = new ChatMessageConverter(JSON);

    @Test
    @DisplayName("citations survive the metadata JSON round trip next to the legacy fields")
    void citationsRoundTripThroughTheStoredMetadata() throws Exception {
        AnswerCitation citation = new AnswerCitation(1, "ret-1:1", "ret-1",
                "44444444-4444-4444-8444-444444444444", "chunk-1",
                new SourceLocation("66666666-6666-4666-8666-666666666666", "《资料》", List.of("章节"),
                        "hash-1", 3, 5),
                "body-v1", "rev-1", "片段正文");
        ChatMessageDTO dto = ChatMessageDTO.builder().sessionId("session").role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("回答")
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                "call-1", "function", "directAnswer", "{\"answer\":\"回答\"}")))
                        .citations(List.of(citation))
                        .build())
                .build();

        ChatMessage entity = converter.toEntity(dto);
        assertTrue(entity.getMetadata().contains("\"citations\""));
        assertTrue(entity.getMetadata().contains("\"toolCalls\""), "legacy fields stay in the same JSON object");

        ChatMessageDTO restored = converter.toDTO(entity);
        assertEquals(List.of(citation), restored.getMetadata().getCitations());
        assertEquals(1, restored.getMetadata().getToolCalls().size());
        assertEquals("call-1", restored.getMetadata().getToolCalls().get(0).id());
        assertNotNull(converter.toVO(entity).getMetadata().getCitations());
    }

    @Test
    @DisplayName("old records without citations still read, and unknown future fields do not break them")
    void oldAndFutureRecordsStayReadable() throws Exception {
        ChatMessage legacyTool = ChatMessage.builder().id("old-tool").sessionId("session").role("tool").content("工具结果")
                .metadata("{\"toolResponse\":{\"id\":\"call-1\",\"name\":\"KnowledgeTool\",\"responseData\":\"{}\"}}")
                .build();
        ChatMessageDTO legacy = converter.toDTO(legacyTool);
        assertNull(legacy.getMetadata().getCitations());
        assertEquals("KnowledgeTool", legacy.getMetadata().getToolResponse().name());

        ChatMessage future = ChatMessage.builder().id("future").sessionId("session").role("assistant").content("回答")
                .metadata("{\"toolCalls\":null,\"runId\":\"run-1\",\"citations\":[{\"label\":1,"
                        + "\"citationId\":\"ret-1:1\",\"retrievalId\":\"ret-1\",\"kbId\":\"kb\",\"chunkId\":\"chunk\","
                        + "\"source\":{\"documentId\":\"doc\",\"documentName\":\"《资料》\",\"headingPath\":[\"章节\"],"
                        + "\"sourceHash\":\"hash\",\"startLine\":1,\"endLine\":2},\"indexVersion\":\"body-v1\","
                        + "\"indexRevision\":\"rev-1\",\"excerpt\":\"片段\"}]}")
                .build();
        ChatMessageDTO restored = converter.toDTO(future);
        assertEquals(1, restored.getMetadata().getCitations().size());
        assertEquals(1, restored.getMetadata().getCitations().get(0).source().startLine());
        assertEquals("片段", restored.getMetadata().getCitations().get(0).excerpt());
    }
}
