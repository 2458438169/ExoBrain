package com.kama.jchatmind.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.citation.AnswerCitation;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.response.GetChatMessagesResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import com.kama.jchatmind.retrieval.SourceLocation;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.DocumentFacadeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 公开精选的引用白名单：只暴露展示字段；来源撤下/删除后整条精选不可见，节选不会继续公开。
 */
class DemoCatalogServiceCitationTest {

    private static final String AGENT = "11111111-1111-4111-8111-111111111111";
    private static final String SHOWCASE = "22222222-2222-4222-8222-222222222222";
    private static final String PUBLIC_KB = "44444444-4444-4444-8444-444444444444";
    private static final String PRIVATE_KB = "55555555-5555-4555-8555-555555555555";
    private static final String DOC = "66666666-6666-4666-8666-666666666666";
    private static final String CALL = "call-1";

    private final DemoSessionRepository sessions = mock(DemoSessionRepository.class);
    private final KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
    private final DocumentMapper documents = mock(DocumentMapper.class);
    private final ChatMessageFacadeService messages = mock(ChatMessageFacadeService.class);
    private final DemoProperties properties = new DemoProperties();
    private final ObjectMapper json = new ObjectMapper();
    private DemoCatalogService catalog;

    @BeforeEach
    void setUp() {
        properties.setAgentId(AGENT);
        properties.setPublicKbIds(List.of(PUBLIC_KB));
        properties.setShowcaseSessionIds(List.of(SHOWCASE));
        catalog = new DemoCatalogService(new DemoAccessPolicy(properties), sessions, knowledgeBases, documents,
                messages, json, mock(DocumentFacadeService.class));
        when(sessions.findShowcase(SHOWCASE, AGENT))
                .thenReturn(ChatSessionVO.builder().id(SHOWCASE).agentId(AGENT).title("示例对话").build());
        when(documents.selectById(DOC)).thenReturn(Document.builder().id(DOC).kbId(PUBLIC_KB).filename("公开资料.md").build());
    }

    @Test
    @DisplayName("the public projection keeps label/source/excerpt and strips the internal retrieval fields")
    void publicProjectionStripsInternalFields() throws Exception {
        publishedAnswer(citation(PUBLIC_KB));

        DemoCatalogService.Showcase showcase = catalog.showcase(SHOWCASE);
        AnswerCitation projected = showcase.chatMessages().get(0).getMetadata().getCitations().get(0);
        assertEquals(1, projected.label());
        assertEquals("《公开资料》", projected.source().documentName());
        assertEquals(List.of("架构"), projected.source().headingPath());
        assertEquals("hash-1", projected.source().sourceHash());
        assertEquals(3, projected.source().startLine());
        assertEquals(5, projected.source().endLine());
        assertEquals("实际使用的片段节选", projected.excerpt());
        assertNull(projected.citationId());
        assertNull(projected.retrievalId());
        assertNull(projected.kbId());
        assertNull(projected.chunkId());
        assertNull(projected.indexVersion());
        assertNull(projected.indexRevision());

        String serialized = json.writeValueAsString(showcase);
        for (String internal : List.of("chunkId", "retrievalId", "indexVersion", "indexRevision", "citationId")) {
            assertFalse(serialized.contains("\"" + internal + "\""), internal + " must not be public: " + serialized);
        }
        assertTrue(serialized.contains("实际使用的片段节选"));
    }

    @Test
    @DisplayName("a withdrawn source hides the showcase instead of exposing its excerpt")
    void privateSourceHidesTheShowcase() {
        publishedAnswer(citation(PRIVATE_KB));
        assertEquals(404, assertThrows(DemoException.class, () -> catalog.showcase(SHOWCASE)).getStatus());
    }

    @Test
    @DisplayName("a deleted document or a document from another knowledge base hides the showcase")
    void deletedOrMismatchedDocumentHidesTheShowcase() {
        publishedAnswer(citation(PUBLIC_KB));
        when(documents.selectById(DOC)).thenReturn(null);
        assertEquals(404, assertThrows(DemoException.class, () -> catalog.showcase(SHOWCASE)).getStatus());

        when(documents.selectById(DOC)).thenReturn(Document.builder().id(DOC).kbId(PRIVATE_KB).filename("别的库.md").build());
        assertEquals(404, assertThrows(DemoException.class, () -> catalog.showcase(SHOWCASE)).getStatus());
    }

    @Test
    @DisplayName("an answer without citations keeps working in the showcase")
    void answerWithoutCitationsStillRenders() {
        ChatMessageVO answer = ChatMessageVO.builder().id("message-1").sessionId(SHOWCASE)
                .role(ChatMessageDTO.RoleType.ASSISTANT).content("普通回答")
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall(CALL, "function", "KnowledgeTool",
                                "{\"kbsId\":\"" + PUBLIC_KB + "\",\"query\":\"问题\"}")))
                        .build())
                .build();
        when(messages.getChatMessagesBySessionId(SHOWCASE))
                .thenReturn(GetChatMessagesResponse.builder().chatMessages(new ChatMessageVO[]{answer}).build());

        DemoCatalogService.Showcase showcase = catalog.showcase(SHOWCASE);
        assertEquals("普通回答", showcase.chatMessages().get(0).getContent());
        assertNull(showcase.chatMessages().get(0).getMetadata().getCitations());
    }

    // ------------------------------------------------------------------ fixtures

    private void publishedAnswer(AnswerCitation citation) {
        ChatMessageVO answer = ChatMessageVO.builder().id("message-1").sessionId(SHOWCASE)
                .role(ChatMessageDTO.RoleType.ASSISTANT).content("带来源的回答")
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall(CALL, "function", "KnowledgeTool",
                                "{\"kbsId\":\"" + PUBLIC_KB + "\",\"query\":\"问题\"}")))
                        .citations(List.of(citation))
                        .build())
                .build();
        when(messages.getChatMessagesBySessionId(SHOWCASE))
                .thenReturn(GetChatMessagesResponse.builder().chatMessages(new ChatMessageVO[]{answer}).build());
    }

    private static AnswerCitation citation(String kbId) {
        return new AnswerCitation(1, "ret-1:1", "ret-1", kbId, "chunk-1",
                new SourceLocation(DOC, "《公开资料》", List.of("架构"), "hash-1", 3, 5),
                "body-v1", "rev-1", "实际使用的片段节选");
    }
}
