package com.kama.jchatmind.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.execution.RunOrigin;
import com.kama.jchatmind.execution.RunRepository;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.runs.RunQueueJob;
import com.kama.jchatmind.runs.RunQueueRepository;
import com.kama.jchatmind.runs.RunQueueSettings;
import com.kama.jchatmind.runs.RunSubmissionException;
import com.kama.jchatmind.runs.RunSubmissionService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolFacadeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 07：提交时固定的快照与幂等键。
 *
 * <p>账号提交不再直接启动执行，而是在同一事务里写用户消息、固定的历史快照、QUEUED 运行与排队任务。
 * 这里验证“之后的数据库修改不能替换本次提交的问题/历史”，以及同一幂等键重试返回原来的 message/run。</p>
 */
class ChatSubmissionSnapshotTest {
    private static final String ACTOR = "11111111-1111-4111-8111-111111111111";
    private static final String AGENT = "22222222-2222-4222-8222-222222222222";
    private static final String SESSION = "33333333-3333-4333-8333-333333333333";
    private static final String HASH = "a".repeat(64);

    private final ChatMessageMapper mapper = mock(ChatMessageMapper.class);
    private final RunQueueRepository jobs = mock(RunQueueRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private RunSubmissionService submissions;

    @BeforeEach
    void setUp() {
        RunRepository runs = mock(RunRepository.class);
        AgentMapper agents = mock(AgentMapper.class);
        when(agents.selectById(AGENT)).thenReturn(Agent.builder().id(AGENT).name("合成助手")
                .model("stub-model").allowedKbs("[]").allowedTools("[]")
                .chatOptions("{\"temperature\":0.7,\"topP\":1.0,\"messageLength\":10}").build());
        when(mapper.selectBySessionIdRecently(SESSION, 100)).thenReturn(List.of(ChatMessage.builder()
                .id("old-message").sessionId(SESSION).role("user").content("previous question").build()));
        when(mapper.insert(any())).thenAnswer(call -> {
            ((ChatMessage) call.getArgument(0)).setId("trigger");
            return 1;
        });
        when(jobs.findByIdempotencyKey(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(jobs.findActiveBySession(anyString())).thenReturn(Optional.empty());
        when(jobs.countActiveForActor(anyString())).thenReturn(0);
        when(jobs.countQueued()).thenReturn(0);
        submissions = new RunSubmissionService(mapper, new ChatMessageConverter(json), json, runs, jobs,
                new RunQueueSettings(), agents, mock(ModelCatalogService.class), publisher);
    }

    @Test
    void submittedMessageAndHistoryCannotBeReplacedByLaterDatabaseEdits() throws Exception {
        CreateChatMessageResponse response = submissions.submitAccount(ACTOR, 4, HASH, AGENT, SESSION,
                "original question", "key-1").toResponse();
        assertEquals("trigger", response.getChatMessageId());
        assertNotNull(response.getRunId());
        assertEquals("QUEUED", response.getRunStatus());
        assertFalse(response.getIdempotentReplay());

        ArgumentCaptor<RunQueueJob> captor = ArgumentCaptor.forClass(RunQueueJob.class);
        verify(jobs).insert(captor.capture());
        RunQueueJob job = captor.getValue();
        assertEquals("original question", job.userInput());
        assertEquals(HASH, job.actorSessionHash());
        assertEquals(4, job.actorAuthVersion());
        assertEquals(0L, job.fencingToken());
        assertEquals(1, job.maxAttempts(), "崩溃的运行不会被自动重放");

        // 后续数据库修改不能影响已经固定的快照。
        when(mapper.selectBySessionIdRecently(anyString(), anyInt())).thenReturn(List.of(
                ChatMessage.builder().role("user").content("later replacement task").build()));
        AgentMapper agents = mock(AgentMapper.class);
        AgentConverter converter = mock(AgentConverter.class);
        Agent entity = Agent.builder().id(AGENT).model("stub-model").build();
        when(agents.selectById(AGENT)).thenReturn(entity);
        when(converter.toDTO(entity)).thenReturn(AgentDTO.builder().id(AGENT).name("Agent").model("stub-model")
                .allowedKbs(List.of()).allowedTools(List.of())
                .chatOptions(AgentDTO.ChatOptions.defaultOptions()).build());
        ChatClientRegistry clients = mock(ChatClientRegistry.class);
        when(clients.get("stub-model")).thenReturn(mock(ChatClient.class));
        ToolFacadeService tools = mock(ToolFacadeService.class);
        when(tools.getFixedTools()).thenReturn(List.of());
        ChatMessageFacadeService laterHistory = mock(ChatMessageFacadeService.class);
        JChatMindFactory factory = new JChatMindFactory(clients, mock(SseService.class), agents, converter,
                mock(KnowledgeBaseMapper.class), mock(KnowledgeBaseConverter.class), tools, laterHistory,
                new ChatMessageConverter(json));
        JChatMind runtime = factory.createForSubmission(job.agentId(), job.sessionId(), job.triggeringMessageId(),
                job.userInput(), job.historySnapshot(), config -> { });
        ChatMemory memory = (ChatMemory) ReflectionTestUtils.getField(runtime, "chatMemory");
        assertNotNull(memory);
        assertEquals(List.of("previous question", "original question"),
                memory.get(SESSION).stream().map(message -> message.getText()).toList());
        assertEquals("original question", ReflectionTestUtils.getField(runtime, "submittedUserInput"));
        verify(laterHistory, times(0)).getChatMessagesBySessionIdRecently(anyString(), anyInt());
        assertThrows(AuthException.class, () -> factory.createForSubmission(job.agentId(), job.sessionId(),
                "trigger", "changed question", job.historySnapshot(), config -> { }));
    }

    @Test
    void theSameIdempotencyKeyReturnsTheOriginalMessageAndRunWithoutANewTask() {
        CreateChatMessageResponse first = submissions.submitAccount(ACTOR, 1, HASH, AGENT, SESSION,
                "question", "key-2").toResponse();
        ArgumentCaptor<RunQueueJob> captor = ArgumentCaptor.forClass(RunQueueJob.class);
        verify(jobs).insert(captor.capture());
        when(jobs.findByIdempotencyKey(RunSubmissionService.actorScope(RunOrigin.ACCOUNT, ACTOR), SESSION, "key-2"))
                .thenReturn(Optional.of(captor.getValue()));

        CreateChatMessageResponse replay = submissions.submitAccount(ACTOR, 1, HASH, AGENT, SESSION,
                "question", "key-2").toResponse();
        assertTrue(replay.getIdempotentReplay(), "重试必须复用原来的提交，而不是再排一次队");
        assertEquals(first.getChatMessageId(), replay.getChatMessageId());
        assertEquals(first.getRunId(), replay.getRunId());
        verify(jobs, times(1)).insert(any());
        verify(mapper, times(1)).insert(any());

        // 同一个键配不同请求内容：明确拒绝，绝不按问题文本静默去重。
        assertThrows(RunSubmissionException.class, () -> submissions.submitAccount(ACTOR, 1, HASH, AGENT, SESSION,
                "a different question", "key-2"));
    }

    @Test
    void queuedStateAndRunIdAreExposedToTheClient() {
        CreateChatMessageResponse response = submissions.submitAccount(ACTOR, 1, HASH, AGENT, SESSION,
                "question", "key-3").toResponse();
        assertNotNull(response.getRunId(), "提交返回的 runId 用于读取排队/执行状态");
        assertEquals("QUEUED", response.getRunStatus());
        assertFalse(response.getIdempotentReplay());
    }
}
