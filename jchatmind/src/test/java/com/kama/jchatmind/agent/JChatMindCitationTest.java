package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.DirectAnswerTool;
import com.kama.jchatmind.citation.AnswerCitation;
import com.kama.jchatmind.citation.CitationRegistry;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.execution.RunLifecycleService;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.retrieval.RetrievalEnvelope;
import com.kama.jchatmind.retrieval.RetrievalHit;
import com.kama.jchatmind.retrieval.SourceLocation;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行时循环里的引用行为：模型只能选择 citationId，服务端按本次运行真实完成的检索结果补全来源。
 *
 * <p>模型调用由 {@link ScriptedRuntime} 回放（不联网、不使用密钥），工具执行走真实的 Spring AI
 * {@code ToolCallingManager} + {@code MethodToolCallback}，因此执行循环、工具响应与消息落盘前的 DTO
 * 都是真实路径。</p>
 */
class JChatMindCitationTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String KB = "44444444-4444-4444-8444-444444444444";
    private static final String OTHER_KB = "55555555-5555-4555-8555-555555555555";
    private static final String DOC = "66666666-6666-4666-8666-666666666666";

    @Test
    @DisplayName("citations are completed from this run's real retrieval, not from the model text")
    void citationsComeFromTheRealRetrievalOfThisRun() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "第一篇片段的正文", 1, 3, 5),
                hit("ret-1:2", "chunk-b", "第二篇片段的正文", 2, 8, 9))));

        List<ChatMessageDTO> saved = new ArrayList<>();
        run(List.of(
                knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "回答正文", "[\"ret-1:2\",\"ret-1:1\"]")),
                saved, List.of(kb(KB)), envelope);

        ChatMessageDTO answer = answerIn(saved);
        List<AnswerCitation> citations = answer.getMetadata().getCitations();
        assertNotNull(citations);
        assertEquals(2, citations.size());
        // 编号由服务端按 (检索序号, rank) 生成：模型给出的顺序（先 2 后 1）不影响编号。
        assertEquals(List.of(1, 2), citations.stream().map(AnswerCitation::label).toList());
        assertEquals(List.of("chunk-a", "chunk-b"), citations.stream().map(AnswerCitation::chunkId).toList());

        AnswerCitation first = citations.get(0);
        assertEquals("ret-1:1", first.citationId());
        assertEquals("ret-1", first.retrievalId());
        assertEquals(KB, first.kbId());
        assertEquals("body-v1", first.indexVersion());
        assertEquals("rev-1", first.indexRevision());
        assertEquals(DOC, first.source().documentId());
        assertEquals("《合成资料》", first.source().documentName());
        assertEquals(List.of("章节"), first.source().headingPath());
        assertEquals("hash-chunk-a", first.source().sourceHash());
        assertEquals(3, first.source().startLine());
        assertEquals(5, first.source().endLine());
        assertEquals("第一篇片段的正文", first.excerpt());
    }

    @Test
    @DisplayName("fabricated, foreign and never-executed ids produce no citation at all")
    void fabricatedOrForeignIdsProduceNoCitations() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(hit("ret-1:1", "chunk-a", "片段", 1, 1, 2))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        run(List.of(
                knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "回答正文",
                        "[\"made-up:1\",\"other-session-retrieval:1\",\"ret-1:99\",\"99999999-9999-4999-8999-999999999999\"]")),
                saved, List.of(kb(KB)), envelope);

        assertNull(answerIn(saved).getMetadata().getCitations(),
                "no citation may be fabricated for unknown ids");
    }

    @Test
    @DisplayName("an answer without citationIds still works (compatibility with the old directAnswer call)")
    void answerWithoutCitationIdsStillWorks() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(hit("ret-1:1", "chunk-a", "片段", 1, 1, 2))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        run(List.of(
                knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "普通答复", null)),
                saved, List.of(kb(KB)), envelope);

        assertNull(answerIn(saved).getMetadata().getCitations());
        assertTrue(saved.stream().anyMatch(message -> message.getRole() == ChatMessageDTO.RoleType.TOOL
                        && message.getContent() != null && message.getContent().contains("答复已交付")),
                "the directAnswer tool still completes a plain answer");
    }

    @Test
    @DisplayName("a source outside the run's own knowledge bases is rejected even though it was really retrieved")
    void sourceOutsideTheRunsKnowledgeBasesIsRejected() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(hit("ret-1:1", "chunk-a", "片段", 1, 1, 2))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        // 本次运行允许的 KB 清单里没有命中来源的 KB（来源已不在获准范围）。
        run(List.of(
                knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "回答正文", "[\"ret-1:1\"]")),
                saved, List.of(kb(OTHER_KB)), envelope);

        assertNull(answerIn(saved).getMetadata().getCitations());
    }

    @Test
    @DisplayName("a source that was never retrieved in this run cannot be registered")
    void unrequestedRetrievalCannotRegisterASource() throws Exception {
        List<ChatMessageDTO> saved = new ArrayList<>();
        ScriptedRuntime runtime = run(List.of(answerRound("call-a1", "回答正文", "[\"ret-1:1\"]")),
                saved, List.of(kb(KB)), envelope(RetrievalEnvelope.ok("ret-1",
                        List.of(hit("ret-1:1", "chunk-a", "片段", 1, 1, 2)))));

        assertNull(answerIn(saved).getMetadata().getCitations());
        assertFalse(runtime.registeredCitations(),
                "a run without a completed retrieval may not register any source");
    }

    @Test
    void toolParameterSpillNeverPersistsAnAnswer() throws Exception {
        List<ChatMessageDTO> saved = new ArrayList<>();
        ChatResponse malformed = answerRound("call-a1",
                "取消后迟到答复不会写入。</answer>\n<parameter name=\"citationIds\">[\"ret-1:1\"]", null);
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> run(List.of(malformed), saved, List.of(), ""));
        assertEquals("INVALID_ANSWER_FORMAT", RunLifecycleService.classify(error));
        assertFalse(saved.stream().anyMatch(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT),
                "the malformed answer must be rejected before persistence");
    }

    @Test
    void fallbackContentUsesTheSameFormatGuard() {
        List<ChatMessageDTO> saved = new ArrayList<>();
        ChatResponse malformed = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("回答</answer>\n<parameter name=\"citationIds\">[]").build())));
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> run(List.of(malformed), saved, List.of(), ""));
        assertEquals("INVALID_ANSWER_FORMAT", RunLifecycleService.classify(error));
        assertTrue(saved.isEmpty());
    }

    @Test
    void xmlExamplesInFencedCodeRemainUnmodified() throws Exception {
        for (String fence : List.of("```", "~~~~")) {
            List<ChatMessageDTO> saved = new ArrayList<>();
            String answer = "XML 示例：\n" + fence + "xml\n</answer>\n<parameter name=\"citationIds\">[]\n"
                    + fence + fence.charAt(0) + "\n这是代码中的示例。";
            run(List.of(answerRound("call-a1", answer, null)), saved, List.of(), "");
            assertEquals(answer, answerIn(saved).getContent());
        }
    }

    @Test
    void inlineProtocolExampleRemainsUnmodified() throws Exception {
        List<ChatMessageDTO> saved = new ArrayList<>();
        String answer = "异常边界的例子是 `</answer><parameter name=\"citationIds\">[]`。";
        run(List.of(answerRound("call-a1", answer, null)), saved, List.of(), "");
        assertEquals(answer, answerIn(saved).getContent());
    }

    @Test
    void aCodeExampleDoesNotHideALaterSpill() throws Exception {
        List<ChatMessageDTO> saved = new ArrayList<>();
        ChatResponse malformed = answerRound("call-a1",
                "```xml\n<answer>示例</answer>\n```\n回答</answer><parameter name=\"citationIds\">[]", null);
        assertThrows(RuntimeException.class, () -> run(List.of(malformed), saved, List.of(), ""));
        assertTrue(saved.isEmpty());
    }

    // ------------------------------------------------------------------ fixtures

    /** 用脚本响应运行一次真实执行循环，返回本次运行的运行时对象以便检查内部登记表。 */
    private static ScriptedRuntime run(List<ChatResponse> script, List<ChatMessageDTO> saved,
                                       List<KnowledgeBaseDTO> knowledgeBases, String retrievalEnvelope) {
        ChatMessageFacadeService messages = mock(ChatMessageFacadeService.class);
        when(messages.createChatMessage(any(ChatMessageDTO.class))).thenAnswer(invocation -> {
            ChatMessageDTO dto = invocation.getArgument(0);
            saved.add(dto);
            return CreateChatMessageResponse.builder().chatMessageId("message-" + saved.size()).build();
        });
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new SyntheticKnowledgeTool(retrievalEnvelope), new DirectAnswerTool())
                .build()
                .getToolCallbacks();
        ScriptedRuntime runtime = new ScriptedRuntime(script, "agent", "Agent", "描述", "系统提示",
                mock(ChatClient.class), 20, List.of(), List.of(callbacks), knowledgeBases, "session",
                mock(SseService.class), messages, new ChatMessageConverter(JSON), () -> { }, "用户问题");
        // 真实 ChatClient 会把工具回调放进这次模型调用的选项（Agent 自己的 chatOptions 因此带上它们）；
        // 脚本化回放不经过网络，这里显式给出同一份选项，让 execute() 里的 ToolCallingManager 解析到同样的工具。
        ReflectionTestUtils.setField(runtime, "chatOptions", DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolCallbacks(callbacks)
                .build());
        runtime.run();
        return runtime;
    }

    private static ChatMessageDTO answerIn(List<ChatMessageDTO> saved) {
        return saved.stream()
                .filter(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                        && !message.getContent().isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no answer message was persisted: " + saved));
    }

    private static ChatResponse knowledgeRound(String toolCallId, String envelope) {
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(toolCallId, "function", "KnowledgeTool",
                        "{\"kbsId\":\"" + KB + "\",\"query\":\"问题\"}")))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse answerRound(String toolCallId, String answer, String citationIdsJson) throws Exception {
        var parameters = JSON.createObjectNode().put("answer", answer);
        if (citationIdsJson != null) parameters.set("citationIds", JSON.readTree(citationIdsJson));
        String arguments = JSON.writeValueAsString(parameters);
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(toolCallId, "function", "directAnswer", arguments)))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    /** 用队列里的脚本替换真实模型调用；think/execute/saveMessage/引用校验都保持生产实现。 */
    private static final class ScriptedRuntime extends JChatMind {
        private final Queue<ChatResponse> responses;

        ScriptedRuntime(List<ChatResponse> script, String agentId, String name, String description, String systemPrompt,
                        ChatClient chatClient, int maxMessages, List<Message> memory, List<ToolCallback> tools,
                        List<KnowledgeBaseDTO> knowledgeBases, String chatSessionId, SseService sseService,
                        ChatMessageFacadeService chatMessageFacadeService, ChatMessageConverter chatMessageConverter,
                        Runnable runtimeGuard, String submittedUserInput) {
            super(agentId, name, description, systemPrompt, chatClient, maxMessages, memory, tools, knowledgeBases,
                    chatSessionId, sseService, chatMessageFacadeService, chatMessageConverter, runtimeGuard,
                    submittedUserInput);
            this.responses = new ConcurrentLinkedQueue<>(script);
        }

        @Override
        ChatResponse requestModel(Prompt prompt, String thinkPrompt) {
            ChatResponse response = responses.poll();
            assertNotNull(response, "the scripted run asked for more model calls than the script provides");
            return response;
        }

        boolean registeredCitations() {
            CitationRegistry registry = (CitationRegistry) ReflectionTestUtils.getField(this, "citationRegistry");
            return registry != null && !registry.isEmpty();
        }
    }

    private static KnowledgeBaseDTO kb(String id) {
        return KnowledgeBaseDTO.builder().id(id).name("合成知识库").build();
    }

    private static RetrievalHit hit(String citationId, String chunkId, String content, int rank, int start, int end) {
        return new RetrievalHit(citationId, chunkId, KB, rank, content,
                new SourceLocation(DOC, "《合成资料》", List.of("章节"), "hash-" + chunkId, start, end),
                "body-v1", "rev-1", RetrievalHit.METRIC_L2, 0.5);
    }

    private static String envelope(RetrievalEnvelope envelope) throws Exception {
        return JSON.writeValueAsString(envelope);
    }

    /** 合成检索工具：与生产工具同名同参，返回真实的契约信封结构（只作为 @Tool 方法提供者，不实现 Tool）。 */
    public static final class SyntheticKnowledgeTool {
        private final String envelope;

        public SyntheticKnowledgeTool(String envelope) {
            this.envelope = envelope;
        }

        @Tool(name = "KnowledgeTool", description = "合成检索结果")
        public String knowledgeQuery(String kbsId, String query) {
            return envelope;
        }
    }
}
