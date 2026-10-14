package com.kama.jchatmind.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.demo.DemoAccessPolicy;
import com.kama.jchatmind.demo.DemoProperties;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.event.listener.ChatEventListener;
import com.kama.jchatmind.mapper.*;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolFacadeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthListenerFailureTest {
    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final String AGENT = "22222222-2222-4222-8222-222222222222";
    private static final String SESSION = "33333333-3333-4333-8333-333333333333";
    private static final String HASH = "a".repeat(64);
    @AfterEach void cleanup() { AuthContext.clear(); }

    @ParameterizedTest
    @ValueSource(strings = {"logout", "expired", "active"})
    @Timeout(15)
    void genericUpstreamFailureOnlySendsCompletionToAnAuthorizedInitiatingLogin(String ending) throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AuthUser user = new AuthUser(USER, "admin", "Admin", "ADMIN", true, Instant.now());
        var validSession = new AuthRepository.LoginSession(user, HASH, "csrf", Instant.now().plusSeconds(300), 1);
        AtomicReference<AuthRepository.LoginSession> current = new AtomicReference<>(validSession);
        AuthContext.set(validSession);
        AuthRepository authRepository = mock(AuthRepository.class);
        when(authRepository.findSession(HASH)).thenAnswer(call -> current.get());
        doAnswer(call -> { current.set(null); return null; }).when(authRepository).revokeSession(HASH);
        PasswordHasher passwords = mock(PasswordHasher.class);
        when(passwords.hash(anyString())).thenReturn("dummy");
        AuthService auth = new AuthService(authRepository, passwords, new AuthProperties(), new LoginRateLimiter());

        AgentMapper agents = mock(AgentMapper.class);
        AgentConverter converter = new AgentConverter(json);
        Agent entity = Agent.builder().id(AGENT).name("Test Agent").model("stub-model").allowedKbs("[]").allowedTools("[]")
                .chatOptions(json.writeValueAsString(AgentDTO.ChatOptions.defaultOptions())).build();
        when(agents.selectById(AGENT)).thenReturn(entity);
        ChatSessionMapper chatSessions = mock(ChatSessionMapper.class);
        when(chatSessions.selectById(SESSION)).thenReturn(ChatSession.builder().id(SESSION).agentId(AGENT).build());
        KnowledgeBaseMapper knowledge = mock(KnowledgeBaseMapper.class);
        DemoProperties publication = new DemoProperties();
        ResourceAccess access = new ResourceAccess(mock(ResourceAclRepository.class), new DemoAccessPolicy(publication),
                publication, agents, converter, knowledge, mock(DocumentMapper.class), chatSessions,
                mock(ChatMessageMapper.class), auth, mock(ModelCatalogService.class));

        SseService sse = mock(SseService.class);
        ChatMessageFacadeService messages = mock(ChatMessageFacadeService.class);
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        CountDownLatch waitingOnModel = new CountDownLatch(1), finishModel = new CountDownLatch(1);
        when(client.prompt(any(Prompt.class)).system(anyString()).toolCallbacks(any(ToolCallback[].class))
                .call().chatClientResponse().chatResponse()).thenAnswer(call -> {
                    waitingOnModel.countDown();
                    assertTrue(finishModel.await(5, TimeUnit.SECONDS));
                    throw new IllegalStateException("synthetic upstream failure");
                });
        ChatClientRegistry clients = mock(ChatClientRegistry.class);
        when(clients.get("stub-model")).thenReturn(client);
        ToolFacadeService tools = mock(ToolFacadeService.class);
        when(tools.getFixedTools()).thenReturn(List.of());
        JChatMindFactory factory = new JChatMindFactory(clients, sse, agents, converter, knowledge,
                new KnowledgeBaseConverter(json), tools, messages, new ChatMessageConverter(json));
        ChatEventListener listener = new ChatEventListener(factory, access, sse);
        String snapshot = json.writeValueAsString(List.of(ChatMessageDTO.builder().id("trigger").sessionId(SESSION)
                .role(ChatMessageDTO.RoleType.USER).content("question").build()));
        ChatEvent event = new ChatEvent(AGENT, SESSION, "question", USER, 1, HASH, "trigger", snapshot);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> listener.handle(event));
            try {
                assertTrue(waitingOnModel.await(5, TimeUnit.SECONDS));
                if ("logout".equals(ending)) auth.logout();
                if ("expired".equals(ending)) current.set(new AuthRepository.LoginSession(user, HASH, "csrf", Instant.now().minusSeconds(1), 1));
            } finally { finishModel.countDown(); }
            result.get(5, TimeUnit.SECONDS);
        }
        verify(messages, never()).createChatMessage(any(ChatMessageDTO.class));
        if ("active".equals(ending)) {
            verify(sse, atLeastOnce()).send(eq(SESSION), argThat(message -> message.getType() == SseMessage.Type.AI_DONE));
        } else {
            verify(sse, never()).send(eq(SESSION), argThat(message -> message.getType() == SseMessage.Type.AI_DONE));
            verify(sse, times(1)).send(eq(SESSION), argThat(message -> message.getType() == SseMessage.Type.AI_THINKING));
        }
    }
}
