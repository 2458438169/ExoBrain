package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.DirectAnswerTool;
import com.kama.jchatmind.auth.AuthException;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.execution.RunEventRecord;
import com.kama.jchatmind.execution.RunEventSink;
import com.kama.jchatmind.execution.RunEventStatus;
import com.kama.jchatmind.execution.RunEventType;
import com.kama.jchatmind.execution.RunRecorder;
import com.kama.jchatmind.execution.TokenUsage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.retrieval.RetrievalEnvelope;
import com.kama.jchatmind.retrieval.RetrievalHit;
import com.kama.jchatmind.retrieval.SourceLocation;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.models.ProviderFailure;
import com.kama.jchatmind.models.ProviderUnavailableException;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 05 执行追踪：用脚本化模型响应回放真实执行循环（{@link JChatMind#requestModel} 边界），
 * 工具执行走真实的 Spring AI {@code ToolCallingManager} + {@code MethodToolCallback}。
 *
 * <p>断言每个真实调用都恰好对应一条事件、耗时非负、模型“计划”但未执行的调用不产生 TOOL_CALL、
 * 重试逐次记录、用量缺失保持 null。</p>
 */
class RunTracingExecutionTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String KB = "44444444-4444-4444-8444-444444444444";
    private static final String DOC = "66666666-6666-4666-8666-666666666666";

    private final List<RunEventRecord> events = new CopyOnWriteArrayList<>();
    private String answerMessageId;
    private int heartbeats;

    private final RunEventSink sink = new RunEventSink() {
        @Override
        public void append(RunEventRecord event) {
            events.add(event);
        }

        @Override
        public void touch(String runId) {
            heartbeats++;
        }

        @Override
        public void setAnswerMessageId(String runId, String messageId) {
            answerMessageId = messageId;
        }
    };

    @Test
    @DisplayName("每次真实模型/工具/检索/落库调用各对应一条事件，toolCallId 与 retrievalId 可关联")
    void everyRealCallHasExactlyOneEvent() throws Exception {
        Harness harness = harness(List.of(
                knowledgeRound("call-k1", envelope()),
                answerRound("call-a1", "回答正文")));

        harness.run();

        assertEquals(List.of(
                        RunEventType.MODEL_CALL,
                        RunEventType.PERSIST_MESSAGE,
                        RunEventType.TOOL_CALL,
                        RunEventType.RETRIEVAL,
                        RunEventType.PERSIST_MESSAGE,
                        RunEventType.MODEL_CALL,
                        RunEventType.PERSIST_MESSAGE,
                        RunEventType.TOOL_CALL,
                        RunEventType.PERSIST_MESSAGE),
                types(), "one event per real call, in the order the calls happened");

        RunEventRecord knowledge = event(RunEventType.TOOL_CALL, "call-k1");
        assertEquals("KnowledgeTool", knowledge.toolName());
        assertEquals(RunEventStatus.SUCCEEDED, knowledge.status());
        RunEventRecord retrieval = event(RunEventType.RETRIEVAL, "call-k1");
        assertEquals("ret-1", retrieval.retrievalId());
        assertEquals("OK", retrieval.detail());
        assertEquals("call-a1", event(RunEventType.TOOL_CALL, "call-a1").toolCallId());

        assertTrue(events.stream().allMatch(e -> e.durationMs() >= 0), "no negative durations");
        assertTrue(events.stream().allMatch(e -> harness.runId().equals(e.runId())), "events belong to this run");
        assertEquals(events.size(), new HashSet<>(events.stream().map(RunEventRecord::sequence).toList()).size(),
                "sequences are unique");
        assertEquals(events.size(), events.getLast().sequence());
        assertTrue(heartbeats >= events.size(), "each appended event refreshes the run heartbeat");

        ChatMessageDTO answer = harness.saved().stream()
                .filter(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                        && "回答正文".equals(message.getContent()))
                .findFirst().orElseThrow();
        assertEquals(answer.getId(), answerMessageId, "the final answer message is linked to the run");
        assertTrue(harness.saved().stream().allMatch(m -> harness.runId().equals(m.getMetadata().getRunId())),
                "persisted messages carry the server-side runId");
        assertEquals(TokenUsage.UNKNOWN.inputTokens(), harness.recorder().usage().inputTokens(),
                "usage that the provider did not report stays null, never 0");
        assertFalse(harness.recorder().traceIncomplete());
    }

    @Test
    @DisplayName("普通问候不伪造工具事件")
    void greetingProducesNoToolEvents() {
        Harness harness = harness(List.of(greetingRound("你好，有什么可以帮你？")));

        harness.run();

        assertEquals(List.of(RunEventType.MODEL_CALL, RunEventType.PERSIST_MESSAGE), types());
        assertNotNull(answerMessageId);
        assertTrue(events.stream().noneMatch(e -> e.type() == RunEventType.TOOL_CALL));
    }

    @Test
    @DisplayName("工具失败只记录真实失败的工具调用，执行继续并以真实调用结束")
    void failingToolIsRecordedAsFailed() throws Exception {
        Harness harness = harness(List.of(
                toolRound("call-f1", "failingTool", "{\"query\":\"boom\"}"),
                answerRound("call-a1", "工具失败后的答复")));

        harness.run();

        RunEventRecord failed = event(RunEventType.TOOL_CALL, "call-f1");
        assertEquals(RunEventStatus.FAILED, failed.status());
        assertEquals("TOOL_FAILED", failed.errorCode());
        assertTrue(failed.durationMs() >= 0);
        assertEquals(2, events.stream().filter(e -> e.type() == RunEventType.MODEL_CALL).count(),
                "the loop continued with a second real model call");
    }

    @Test
    @DisplayName("模型两次繁忙后成功：每次尝试单独记录 attempt 与安全结果分类")
    void twoRetriesAreRecordedPerAttempt() {
        Harness harness = harness(List.of(busyRound(), busyRound(), greetingRound("最终答复")));

        harness.run();

        List<RunEventRecord> modelEvents = events.stream()
                .filter(e -> e.type() == RunEventType.MODEL_CALL || e.type() == RunEventType.MODEL_RETRY)
                .toList();
        assertEquals(List.of(1, 2, 3), modelEvents.stream().map(RunEventRecord::retryAttempt).toList());
        assertEquals(List.of(RunEventType.MODEL_CALL, RunEventType.MODEL_RETRY, RunEventType.MODEL_RETRY), types(modelEvents));
        assertEquals(RunEventStatus.FAILED, modelEvents.get(0).status());
        assertEquals("UPSTREAM_BUSY", modelEvents.get(0).errorCode());
        assertEquals(RunEventStatus.SUCCEEDED, modelEvents.get(2).status());
        assertNull(modelEvents.get(2).errorCode());
        assertTrue(harness.recorder().durationMillis() >= 3000L, "the real backoff (1s + 2s) is inside the run duration");
    }


    @Test
    @DisplayName("限流失败：事件里留白名单诊断，并按 Retry-After 做有界重试")
    void throttledFailureIsDiagnosedAndRetriedWithinBounds() {
        Harness harness = harness(List.of(greetingRound("最终答复")));
        harness.runtime().throttleOnce(new ProviderFailure(429, "1", "rate_limit_exceeded", "req-abc123"), 1);

        harness.run();

        List<RunEventRecord> modelEvents = events.stream()
                .filter(e -> e.type() == RunEventType.MODEL_CALL || e.type() == RunEventType.MODEL_RETRY)
                .toList();
        assertEquals(2, modelEvents.size(), "一次限流 + 一次成功，只记两条真实调用");
        RunEventRecord throttled = modelEvents.get(0);
        assertEquals(RunEventStatus.FAILED, throttled.status());
        assertEquals("MODEL_CALL_FAILED", throttled.errorCode());
        // detail 只允许白名单字段，且带上「本次调用序号 + 是否已检索」，用于分开看首次与后续调用。
        String detail = throttled.detail();
        assertNotNull(detail);
        assertTrue(detail.startsWith("call=1;retrieval=false"), "unexpected detail: " + detail);
        assertTrue(detail.contains("http=429"), "unexpected detail: " + detail);
        assertTrue(detail.contains("retry-after=1"), "unexpected detail: " + detail);
        assertTrue(detail.contains("code=rate_limit_exceeded"), "unexpected detail: " + detail);
        assertTrue(detail.length() <= 200, "detail must fit the run event column");
        assertEquals(RunEventStatus.SUCCEEDED, modelEvents.get(1).status());
        assertTrue(modelEvents.get(1).detail().startsWith("call=2;retrieval=false"));
    }

    @Test
    @DisplayName("达到步数上限时记录 STEP_LIMIT，而不是把它当成一次成功工具")
    void stepLimitIsRecorded() {
        List<ChatResponse> script = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            script.add(knowledgeRound("call-" + i, envelope()));
        }
        Harness harness = harness(script);

        harness.run();

        assertEquals(20, events.stream().filter(e -> e.type() == RunEventType.MODEL_CALL).count());
        assertEquals(20, events.stream().filter(e -> e.type() == RunEventType.TOOL_CALL).count());
        RunEventRecord limit = events.stream().filter(e -> e.type() == RunEventType.STEP_LIMIT).findFirst()
                .orElseThrow(() -> new AssertionError("no STEP_LIMIT event: " + types()));
        assertEquals(RunEventStatus.SUCCEEDED, limit.status());
        assertNull(limit.toolCallId(), "a step limit is not a tool call");
    }

    @Test
    @DisplayName("授权撤销后不再记录“已完成”的工具或落库事件，异常向上抛")
    void revokedAuthorizationStopsTheTrace() {
        Harness harness = harness(List.of(knowledgeRound("call-k1", envelope())), true);

        RuntimeException error = assertThrows(RuntimeException.class, harness::run);

        assertNotNull(error);
        assertEquals(List.of(RunEventType.MODEL_CALL, RunEventType.PERSIST_MESSAGE), types(),
                "no completed tool/persistence event may be written after the revocation");
        assertNull(answerMessageId);
    }

    @Test
    @DisplayName("没有答复结束时保留 ERROR 终态，不关联答复消息")
    void noAnswerEndsInError() {
        Harness harness = harness(List.of(emptyRound(), emptyRound()));

        harness.run();

        assertEquals(AgentState.ERROR, harness.runtime().state());
        assertNull(answerMessageId, "no answer message may be linked when nothing was delivered");
        assertTrue(events.stream().noneMatch(e -> e.type() == RunEventType.ERROR),
                "the runtime itself never fabricates a terminal error event; the caller classifies it");
    }

    @Test
    @DisplayName("服务商提供的用量按字段累加；未提供时保持 null")
    void providerUsageIsSummedPerField() {
        Harness harness = harness(List.of(
                knowledgeRoundWithUsage("call-k1", envelope(), 11, 5),
                answerRoundWithUsage("call-a1", "回答正文", 7, 3)));

        harness.run();

        TokenUsage usage = harness.recorder().usage();
        assertEquals(18, usage.inputTokens());
        assertEquals(8, usage.outputTokens());
        assertEquals(26, usage.totalTokens());
    }

    @Test
    @DisplayName("节流等待期间运行失效：一次模型调用都不发")
    void cancellationDuringThrottleWaitSkipsTheModelCall() {
        AtomicInteger guardCalls = new AtomicInteger();
        // 第一次检查放行（进入等待），等待中途的复查抛失效 → 不可能走到真实发送。
        Harness harness = harness(List.of(greetingRound("不该被调用")), () -> {
            if (guardCalls.incrementAndGet() > 1) {
                throw AuthException.forbidden();
            }
        });
        harness.runtime().attachModelRequestGate(() -> 3_000_000_000L);

        RuntimeException error = assertThrows(RuntimeException.class, harness::run);

        assertEquals(0, harness.runtime().modelCalls(), "等待中失效后不得发出模型调用");
        assertEquals("AUTHORIZATION_REVOKED", com.kama.jchatmind.execution.RunLifecycleService.classify(error));
        assertTrue(events.stream().noneMatch(e -> e.type() == RunEventType.MODEL_CALL),
                "没有真实调用就不该有模型调用事件");
    }

    @Test
    @DisplayName("节流等待被中断：不放行模型调用，且线程能正常结束")
    void interruptionDuringThrottleWaitSkipsTheModelCall() {
        Harness harness = harness(List.of(greetingRound("不该被调用")));
        harness.runtime().attachModelRequestGate(() -> 5_000_000_000L);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                harness.run();
            } catch (RuntimeException failure) {
                thrown.set(failure);
            }
        }, "throttle-wait-probe");
        worker.start();
        try {
            Thread.sleep(400);
            worker.interrupt();
            worker.join(5_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        assertFalse(worker.isAlive(), "被中断的等待必须尽快结束，而不是继续等满整个间隔");
        assertEquals(0, harness.runtime().modelCalls(), "中断之后不得发出模型调用");
        assertNotNull(thrown.get(), "等待被中断应向上抛出，而不是静默继续");
    }

    // ------------------------------------------------------------------ harness

    private Harness harness(List<ChatResponse> script) {
        return harness(script, () -> { });
    }

    private Harness harness(List<ChatResponse> script, boolean revokeAfterKnowledgeTool) {
        SyntheticKnowledgeTool knowledge = new SyntheticKnowledgeTool(envelope());
        Runnable guard = revokeAfterKnowledgeTool
                ? () -> {
                    if (knowledge.invoked) {
                        throw AuthException.forbidden();
                    }
                }
                : () -> { };
        return harness(script, guard, knowledge);
    }

    private Harness harness(List<ChatResponse> script, Runnable guard) {
        return harness(script, guard, new SyntheticKnowledgeTool(envelope()));
    }

    private Harness harness(List<ChatResponse> script, Runnable guard, SyntheticKnowledgeTool knowledge) {
        List<ChatMessageDTO> saved = new ArrayList<>();
        ChatMessageFacadeService messages = mock(ChatMessageFacadeService.class);
        when(messages.createChatMessage(any(ChatMessageDTO.class))).thenAnswer(invocation -> {
            ChatMessageDTO dto = invocation.getArgument(0);
            saved.add(dto);
            return CreateChatMessageResponse.builder().chatMessageId("message-" + saved.size()).build();
        });
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(knowledge, new FailingTool(), new DirectAnswerTool())
                .build()
                .getToolCallbacks();
        String runId = "run-" + java.util.UUID.randomUUID();
        RunRecorder recorder = new RunRecorder(runId, sink, System.nanoTime());
        Runtime runtime = new Runtime(script, callbacks, mock(SseService.class), messages, guard);
        runtime.attachRun(recorder);
        return new Harness(runId, recorder, runtime, saved);
    }

    private record Harness(String runId, RunRecorder recorder, Runtime runtime, List<ChatMessageDTO> saved) {
        void run() {
            runtime.run();
        }
    }

    private List<RunEventType> types() {
        return types(events);
    }

    private static List<RunEventType> types(List<RunEventRecord> events) {
        return events.stream().map(RunEventRecord::type).toList();
    }

    private RunEventRecord event(RunEventType type, String toolCallId) {
        return events.stream()
                .filter(e -> e.type() == type && toolCallId.equals(e.toolCallId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " event for " + toolCallId + ": " + events));
    }

    /** 脚本化模型调用；工具执行、落库前的 DTO、事件记录都走生产实现。 */
    private static final class Runtime extends JChatMind {
        private final Queue<ChatResponse> responses;
        private ProviderFailure pendingFailure;
        private int pendingFailureTimes;
        private final AtomicInteger modelCalls = new AtomicInteger();

        Runtime(List<ChatResponse> script, ToolCallback[] callbacks, SseService sse,
                ChatMessageFacadeService messages, Runnable runtimeGuard) {
            super("11111111-1111-4111-8111-111111111111", "合成助手", "描述", "系统提示",
                    mock(ChatClient.class), 20, List.<Message>of(), List.of(callbacks),
                    List.<KnowledgeBaseDTO>of(), "22222222-2222-4222-8222-222222222222", sse, messages,
                    new ChatMessageConverter(JSON), runtimeGuard, "用户问题");
            this.responses = new ConcurrentLinkedQueue<>(script);
        }

        /** 让接下来的 N 次真实调用直接抛供应商失败（用于限流路径，不联网）。 */
        void throttleOnce(ProviderFailure failure, int times) {
            this.pendingFailure = failure;
            this.pendingFailureTimes = times;
        }

        /** 真实进入模型委托的次数：取消/中断路径必须停在 0。 */
        int modelCalls() {
            return modelCalls.get();
        }

        @Override
        ChatResponse requestModel(Prompt prompt, String thinkPrompt) {
            modelCalls.incrementAndGet();
            if (pendingFailureTimes > 0) {
                pendingFailureTimes--;
                throw new ProviderUnavailableException(pendingFailure);
            }
            ChatResponse response = responses.poll();
            assertNotNull(response, "the scripted run asked for more model calls than the script provides");
            return response;
        }
    }

    private static ChatResponse knowledgeRound(String toolCallId, String envelope) {
        return toolRound(toolCallId, "KnowledgeTool",
                "{\"kbsId\":\"" + KB + "\",\"query\":\"问题\"}", envelope);
    }

    private static ChatResponse knowledgeRoundWithUsage(String toolCallId, String envelope,
                                                        int promptTokens, int completionTokens) {
        return withUsage(toolRound(toolCallId, "KnowledgeTool",
                "{\"kbsId\":\"" + KB + "\",\"query\":\"问题\"}", envelope), promptTokens, completionTokens);
    }

    private static ChatResponse toolRound(String toolCallId, String name, String arguments) {
        return toolRound(toolCallId, name, arguments, "{}");
    }

    private static ChatResponse toolRound(String toolCallId, String name, String arguments, String result) {
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(toolCallId, "function", name, arguments)))
                .build();
        return new ChatResponse(List.of(new Generation((AssistantMessage) message)));
    }

    private static ChatResponse answerRound(String toolCallId, String answer) {
        return toolRound(toolCallId, "directAnswer", "{\"answer\":\"" + answer + "\"}");
    }

    private static ChatResponse answerRoundWithUsage(String toolCallId, String answer,
                                                     int promptTokens, int completionTokens) {
        return withUsage(answerRound(toolCallId, answer), promptTokens, completionTokens);
    }

    private static ChatResponse busyRound() {
        AssistantMessage message = AssistantMessage.builder().content("资源繁忙，请稍后再试").build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse greetingRound(String text) {
        AssistantMessage message = AssistantMessage.builder().content(text).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse emptyRound() {
        AssistantMessage message = AssistantMessage.builder().content("").build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse withUsage(ChatResponse response, int promptTokens, int completionTokens) {
        return new ChatResponse(response.getResults(), ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens)).build());
    }

    private static String envelope() {
        return envelope(RetrievalEnvelope.ok("ret-1", List.of(new RetrievalHit("ret-1:1", "chunk-a", KB, 1,
                "片段正文", new SourceLocation(DOC, "《合成资料》", List.of("章节"), "hash-a", 1, 2),
                "body-v1", "rev-1", RetrievalHit.METRIC_L2, 0.5))));
    }

    private static String envelope(RetrievalEnvelope envelope) {
        try {
            return JSON.writeValueAsString(envelope);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** 与生产工具同名同参的合成检索工具，返回真实的契约信封。 */
    public static final class SyntheticKnowledgeTool {
        private final String envelope;
        private volatile boolean invoked;

        public SyntheticKnowledgeTool(String envelope) {
            this.envelope = envelope;
        }

        @Tool(name = "KnowledgeTool", description = "合成检索结果")
        public String knowledgeQuery(String kbsId, String query) {
            invoked = true;
            return envelope;
        }
    }

    public static final class FailingTool {
        @Tool(name = "failingTool", description = "总是失败的工具")
        public String failingQuery(String query) {
            throw new IllegalStateException("tool exploded");
        }
    }
}