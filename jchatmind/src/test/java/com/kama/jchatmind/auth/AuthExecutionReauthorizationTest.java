package com.kama.jchatmind.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.demo.DemoAccessPolicy;
import com.kama.jchatmind.demo.DemoExecutionService;
import com.kama.jchatmind.demo.DemoProperties;
import com.kama.jchatmind.execution.RunOrigin;
import com.kama.jchatmind.execution.RunRepository;
import com.kama.jchatmind.execution.RunStatus;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.runs.RunResultWriter;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.runs.RunAuthorization;
import com.kama.jchatmind.runs.RunExecutor;
import com.kama.jchatmind.runs.RunLease;
import com.kama.jchatmind.runs.RunQueueJob;
import com.kama.jchatmind.runs.RunQueueRepository;
import com.kama.jchatmind.runs.RunQueueStatus;
import com.kama.jchatmind.runs.RunTerminalWriter;
import com.kama.jchatmind.runs.RunExecutor;
import com.kama.jchatmind.runs.RunLease;
import com.kama.jchatmind.runs.RunQueueRepository;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolFacadeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 07：排队执行开始/进行时的重新授权。
 *
 * <p>任务已经入队不等于永久获得执行许可：worker 在执行前重新校验账号、会话与助手配置，执行中的
 * 每个检查点也继续复用同一套 {@link ResourceAccess} 校验。撤销/登出后不能落库新的消息，也不能
 * 收到迟到的完成事件；仍然有效的登录会收到一条诚实的失败提示。</p>
 */
class AuthExecutionReauthorizationTest {
    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final String AGENT = "22222222-2222-4222-8222-222222222222";
    private static final String SESSION = "33333333-3333-4333-8333-333333333333";
    private static final String HASH = "a".repeat(64);

    private final SseService sse = mock(SseService.class);
    private final ChatMessageFacadeService messages = mock(ChatMessageFacadeService.class);
    private final RunQueueRepository jobs = mock(RunQueueRepository.class);
    private final RunTerminalWriter terminal = mock(RunTerminalWriter.class);
    private final RunRepository runs = mock(RunRepository.class);

    @AfterEach
    void cleanup() {
        AuthContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"logout", "expired"})
    @Timeout(15)
    void revokedSessionNeverPersistsAnAnswerOrReceivesALateCompletion(String ending) throws Exception {
        AuthRepository.LoginSession live = login();
        var current = new java.util.concurrent.atomic.AtomicReference<>(live);
        AuthService auth = authService(current);
        ResourceAccess access = access(auth);
        ChatClientRegistry clients = mock(ChatClientRegistry.class);
        when(clients.get("stub-model")).thenReturn(mock(ChatClient.class));
        JChatMindFactory factory = new JChatMindFactory(clients, sse, mapperForAgent(), new AgentConverter(json()),
                mock(KnowledgeBaseMapper.class), new KnowledgeBaseConverter(json()), mock(ToolFacadeService.class),
                messages, new ChatMessageConverter(json()));
        RunExecutor executor = executor(factory, access, auth);

        AuthContext.set(live);
        if ("logout".equals(ending)) {
            doLogout(auth);
        } else {
            current.set(new AuthRepository.LoginSession(live.user(), HASH, "csrf",
                    Instant.now().minusSeconds(1), 1));
        }

        RunLease lease = lease(job());
        executor.execute(job(), lease);

        verify(messages, never()).createChatMessage(any(ChatMessageDTO.class));
        verify(sse, never()).send(anyString(), any(SseMessage.class));
        verify(terminal).finish(eq(job().id()), eq(0L), eq(job().runId()), eq(RunStatus.FAILED),
                any(), any(), eq("AUTHORIZATION_REVOKED"), eq(false));
    }

    @Test
    @Timeout(15)
    void anActiveSessionGetsOneHonestFailureCompletionAndNoFabricatedAnswer() throws Exception {
        AuthRepository.LoginSession live = login();
        var current = new java.util.concurrent.atomic.AtomicReference<>(live);
        AuthService auth = authService(current);
        ResourceAccess access = access(auth);
        ChatClient client = mock(ChatClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(client.prompt(any(Prompt.class)).system(anyString()).toolCallbacks(any(ToolCallback[].class))
                .call().chatClientResponse().chatResponse())
                .thenThrow(new IllegalStateException("synthetic upstream failure"));
        ChatClientRegistry clients = mock(ChatClientRegistry.class);
        when(clients.get("stub-model")).thenReturn(client);
        ToolFacadeService tools = mock(ToolFacadeService.class);
        when(tools.getFixedTools()).thenReturn(List.of());
        JChatMindFactory factory = new JChatMindFactory(clients, sse, mapperForAgent(), new AgentConverter(json()),
                mock(KnowledgeBaseMapper.class), new KnowledgeBaseConverter(json()), tools, messages,
                new ChatMessageConverter(json()));
        RunExecutor executor = executor(factory, access, auth);

        AuthContext.set(live);
        executor.execute(job(), lease(job()));

        verify(messages, never()).createChatMessage(any(ChatMessageDTO.class));
        verify(sse, org.mockito.Mockito.atLeastOnce()).send(eq(SESSION), org.mockito.ArgumentMatchers.argThat(
                message -> message.getType() == SseMessage.Type.AI_DONE));
        verify(terminal).finish(eq(job().id()), eq(0L), eq(job().runId()), eq(RunStatus.FAILED),
                any(), any(), eq("EXECUTION_FAILED"), eq(false));
    }

    // ------------------------------------------------------------------ fixtures

    private RunExecutor executor(JChatMindFactory factory, ResourceAccess access, AuthService auth) {
        RunAuthorization authorization = mock(RunAuthorization.class);
        when(authorization.revalidate(any())).thenReturn(AgentDTO.builder().id(AGENT).name("合成助手")
                .model("stub-model").allowedKbs(List.of()).allowedTools(List.of())
                .chatOptions(AgentDTO.ChatOptions.defaultOptions()).build());
        when(jobs.startRun(anyString(), any(Instant.class), anyLong())).thenReturn(true);
        when(jobs.renew(anyString(), anyLong(), anyInt())).thenReturn(true);
        when(terminal.finish(anyString(), anyLong(), anyString(), any(RunStatus.class), any(), any(),
                any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        DemoProperties demoProperties = new DemoProperties();
        demoProperties.setAgentId(AGENT);
        DemoAccessPolicy demo = new DemoAccessPolicy(demoProperties);
        DemoExecutionService demoRuntime = mock(DemoExecutionService.class);
        RunResultWriter resultWriter = mock(RunResultWriter.class);
        when(resultWriter.persistMessage(anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString(),
                any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(invocation -> "message-" + java.util.UUID.randomUUID());
        return new RunExecutor(factory, demoRuntime, authorization, access, demo, sse, jobs, runs, resultWriter);
    }

    private RunLease lease(RunQueueJob job) {
        return new RunLease(job, jobs, terminal, runs, 120);
    }

    private RunQueueJob job() {
        return new RunQueueJob("44444444-4444-4444-8444-444444444444",
                "55555555-5555-4555-8555-555555555555", RunOrigin.ACCOUNT, SESSION, "trigger", AGENT,
                "ACCOUNT:" + USER, USER, 1, HASH, null, "key", "d".repeat(64), "question",
                "[{\"id\":\"trigger\",\"sessionId\":\"" + SESSION
                        + "\",\"role\":\"user\",\"content\":\"question\"}]",
                RunQueueStatus.QUEUED, 0, 1, false, null, null, 0L, null, Instant.now(), null, null);
    }

    private AuthRepository.LoginSession login() {
        AuthUser user = new AuthUser(USER, "demo", "Demo", "ADMIN", true, Instant.now());
        return new AuthRepository.LoginSession(user, HASH, "csrf", Instant.now().plusSeconds(300), 1);
    }

    private AuthService authService(java.util.concurrent.atomic.AtomicReference<AuthRepository.LoginSession> current) {
        AuthRepository repository = mock(AuthRepository.class);
        when(repository.findSession(HASH)).thenAnswer(call -> current.get());
        when(repository.findSession(anyString())).thenAnswer(call -> current.get());
        org.mockito.Mockito.doAnswer(call -> {
            current.set(null);
            return null;
        }).when(repository).revokeSession(HASH);
        PasswordHasher passwords = mock(PasswordHasher.class);
        when(passwords.hash(anyString())).thenReturn("dummy");
        return new AuthService(repository, passwords, new AuthProperties(), new LoginRateLimiter());
    }

    private void doLogout(AuthService auth) {
        auth.logout();
    }

    private ResourceAccess access(AuthService auth) {
        AgentMapper agents = mapperForAgent();
        ChatSessionMapper sessions = mock(ChatSessionMapper.class);
        when(sessions.selectById(SESSION)).thenReturn(ChatSession.builder().id(SESSION).agentId(AGENT).build());
        DemoProperties properties = new DemoProperties();
        properties.setAgentId(AGENT);
        return new ResourceAccess(mock(ResourceAclRepository.class), new DemoAccessPolicy(properties),
                properties, agents, new AgentConverter(json()), mock(KnowledgeBaseMapper.class),
                mock(DocumentMapper.class), sessions, mock(ChatMessageMapper.class), auth,
                mock(ModelCatalogService.class));
    }

    private AgentMapper mapperForAgent() {
        AgentMapper agents = mock(AgentMapper.class);
        try {
            Agent entity = Agent.builder().id(AGENT).name("合成助手").model("stub-model").allowedKbs("[]")
                    .allowedTools("[]")
                    .chatOptions(json().writeValueAsString(AgentDTO.ChatOptions.defaultOptions())).build();
            when(agents.selectById(AGENT)).thenReturn(entity);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        return agents;
    }

    private static ObjectMapper json() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
