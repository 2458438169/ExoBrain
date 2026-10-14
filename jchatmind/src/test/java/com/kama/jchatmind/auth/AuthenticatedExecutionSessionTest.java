package com.kama.jchatmind.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticatedExecutionSessionTest {
    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final String HASH = "a".repeat(64), OTHER_LOGIN = "b".repeat(64);
    private final AuthRepository repository = mock(AuthRepository.class);
    private final ConcurrentHashMap<String, AuthRepository.LoginSession> sessions = new ConcurrentHashMap<>();
    private final AuthUser admin = new AuthUser(USER, "admin", "Admin", "ADMIN", true, Instant.now());
    private final ChatMessageFacadeService messages = mock(ChatMessageFacadeService.class);
    private final SseService sse = mock(SseService.class);
    private AuthService auth;

    @BeforeEach void setup() {
        PasswordHasher passwords = mock(PasswordHasher.class);
        when(passwords.hash(anyString())).thenReturn("dummy");
        auth = new AuthService(repository, passwords, new AuthProperties(), new LoginRateLimiter());
        sessions.put(HASH, session(admin, HASH, Instant.now().plusSeconds(300), 1));
        sessions.put(OTHER_LOGIN, session(admin, OTHER_LOGIN, Instant.now().plusSeconds(300), 1));
        AuthContext.set(sessions.get(HASH));
        when(repository.findSession(anyString())).thenAnswer(call -> sessions.get(call.getArgument(0)));
        doAnswer(call -> { sessions.remove(call.getArgument(0)); return null; }).when(repository).revokeSession(anyString());
    }
    @AfterEach void cleanup() { AuthContext.clear(); }

    @ParameterizedTest
    @ValueSource(strings = {"logout", "expired"})
    void queuedRuntimeAndAlreadyConstructedToolStopWhenTheInitiatingLoginEnds(String reason) {
        ChatClient client = mock(ChatClient.class);
        JChatMind runtime = runtime(client);
        ToolCallback tool = mock(ToolCallback.class);
        var scoped = new ScopedToolCallback(tool, Set.of(), this::authorize);
        revoke(reason);
        assertEquals(401, assertThrows(AuthException.class, runtime::run).getStatus());
        assertEquals(401, assertThrows(AuthException.class, () -> scoped.call("{}")).getStatus());
        verifyNoInteractions(client, tool, messages, sse);
        // Ending one login does not log the same account out on a different device.
        assertEquals(admin, auth.currentActor(USER, 1, OTHER_LOGIN));
    }

    @ParameterizedTest
    @CsvSource({"logout,plain", "logout,direct", "logout,busy", "expired,plain", "expired,direct", "expired,busy",
            "disabled,plain", "disabled,direct", "role_changed,plain", "role_changed,direct"})
    @Timeout(15)
    void lateModelResponsesCannotBeSavedPushedOrRetriedAfterRevocation(String reason, String replyKind) throws Exception {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        CountDownLatch waitingOnModel = new CountDownLatch(1), returnResponse = new CountDownLatch(1);
        AssistantMessage output = "direct".equals(replyKind)
                ? AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "directAnswer", "{\"answer\":\"late response\"}"))).build()
                : new AssistantMessage("busy".equals(replyKind) ? "server overloaded" : "late response");
        when(client.prompt(any(Prompt.class)).system(anyString()).toolCallbacks(any(ToolCallback[].class))
                .call().chatClientResponse().chatResponse()).thenAnswer(call -> {
                    waitingOnModel.countDown();
                    assertTrue(returnResponse.await(5, TimeUnit.SECONDS));
                    return new ChatResponse(List.of(new Generation(output)));
                });
        JChatMind runtime = runtime(client);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(runtime::run);
            try {
                assertTrue(waitingOnModel.await(5, TimeUnit.SECONDS));
                revoke(reason);
            } finally { returnResponse.countDown(); }
            ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(AuthException.class, failure.getCause().getCause());
        }
        verify(messages, never()).createChatMessage(any(ChatMessageDTO.class));
        verify(sse, never()).send(anyString(), argThat(event -> event.getType() == SseMessage.Type.AI_GENERATED_CONTENT));
        // Only the initial authorized "thinking" event can have been sent; no late completion escapes.
        verify(sse, times(1)).send(anyString(), any());
    }

    @Test void aToolResultIsNotPersistedAfterLogoutWhileTheToolWasRunning() {
        ChatClient client = mock(ChatClient.class);
        JChatMind runtime = runtime(client);
        var result = org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                        "call", "KnowledgeTool", "late knowledge result"))).build();
        var manager = mock(org.springframework.ai.model.tool.ToolCallingManager.class);
        var execution = mock(org.springframework.ai.model.tool.ToolExecutionResult.class);
        when(execution.conversationHistory()).thenReturn(List.of(result));
        when(manager.executeToolCalls(any(Prompt.class), any(ChatResponse.class))).thenAnswer(call -> {
            revoke("logout"); return execution;
        });
        org.springframework.test.util.ReflectionTestUtils.setField(runtime, "toolCallingManager", manager);
        org.springframework.test.util.ReflectionTestUtils.setField(runtime, "lastChatResponse", new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call", "function", "KnowledgeTool", "{}"))).build()))));
        assertThrows(AuthException.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(runtime, "execute"));
        verifyNoInteractions(messages);
        verify(sse, never()).send(anyString(), argThat(event -> event.getType() == SseMessage.Type.AI_GENERATED_CONTENT));
    }

    private JChatMind runtime(ChatClient client) {
        return new JChatMind("agent", "name", "description", "", client, 10,
                List.of(new UserMessage("original question")), List.of(), List.of(), "chat-session", sse, messages,
                new ChatMessageConverter(new ObjectMapper()), this::authorize, "original question");
    }
    private void authorize() { auth.currentActor(USER, 1, HASH); }
    private void revoke(String reason) {
        switch (reason) {
            case "logout" -> auth.logout();
            case "expired" -> sessions.put(HASH, session(admin, HASH, Instant.now().minusSeconds(1), 1));
            case "disabled" -> sessions.put(HASH, session(new AuthUser(USER, "admin", "Admin", "ADMIN", false, admin.createdAt()),
                    HASH, Instant.now().plusSeconds(300), 1));
            case "role_changed" -> sessions.put(HASH, session(new AuthUser(USER, "admin", "Admin", "USER", true, admin.createdAt()),
                    HASH, Instant.now().plusSeconds(300), 2));
            default -> throw new AssertionError(reason);
        }
    }
    private AuthRepository.LoginSession session(AuthUser user, String hash, Instant expires, int version) {
        return new AuthRepository.LoginSession(user, hash, "csrf", expires, version);
    }
}
