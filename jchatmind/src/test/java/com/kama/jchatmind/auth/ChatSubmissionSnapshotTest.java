package com.kama.jchatmind.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolFacadeService;
import com.kama.jchatmind.service.impl.ChatMessageFacadeServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatSubmissionSnapshotTest {
    private static final String ACTOR = "11111111-1111-4111-8111-111111111111", HASH = "a".repeat(64);
    @AfterEach void cleanup() { AuthContext.clear(); }

    @Test void submittedMessageAndHistoryCannotBeReplacedByLaterDatabaseEdits() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ChatMessageConverter messageConverter = new ChatMessageConverter(json);
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        var service = new ChatMessageFacadeServiceImpl(mapper, messageConverter, publisher, json);
        AuthContext.set(new AuthRepository.LoginSession(new AuthUser(ACTOR, "demo", "Demo", "USER", true, Instant.now()),
                HASH, "csrf", Instant.now().plusSeconds(300), 4));
        ChatMessage old = ChatMessage.builder().id("old-message").sessionId("session").role("user").content("previous question").build();
        when(mapper.selectBySessionIdRecently("session", 100)).thenReturn(List.of(old));
        when(mapper.insert(any())).thenAnswer(call -> { ((ChatMessage) call.getArgument(0)).setId("trigger"); return 1; });
        service.createChatMessage(CreateChatMessageRequest.builder().agentId("agent").sessionId("session")
                .role(ChatMessageDTO.RoleType.USER).content("original question").build());
        ArgumentCaptor<ChatEvent> captor = ArgumentCaptor.forClass(ChatEvent.class);
        verify(publisher).publishEvent(captor.capture());
        ChatEvent event = captor.getValue();
        assertEquals("trigger", event.getTriggeringMessageId());
        assertEquals("original question", event.getUserInput());
        assertEquals(HASH, event.getActorSessionHash());
        assertEquals(4, event.getActorAuthVersion());
        old.setContent("later hostile edit");
        when(mapper.selectBySessionIdRecently(anyString(), anyInt())).thenReturn(List.of(old,
                ChatMessage.builder().role("user").content("later replacement task").build()));

        AgentMapper agents = mock(AgentMapper.class);
        AgentConverter converter = mock(AgentConverter.class);
        Agent entity = Agent.builder().id("agent").model("model").build();
        when(agents.selectById("agent")).thenReturn(entity);
        when(converter.toDTO(entity)).thenReturn(AgentDTO.builder().id("agent").name("Agent").model("model")
                .allowedKbs(List.of()).allowedTools(List.of()).chatOptions(AgentDTO.ChatOptions.defaultOptions()).build());
        ChatClientRegistry clients = mock(ChatClientRegistry.class);
        when(clients.get("model")).thenReturn(mock(ChatClient.class));
        ToolFacadeService tools = mock(ToolFacadeService.class);
        when(tools.getFixedTools()).thenReturn(List.of());
        ChatMessageFacadeService laterHistory = mock(ChatMessageFacadeService.class);
        JChatMindFactory factory = new JChatMindFactory(clients, mock(SseService.class), agents, converter,
                mock(KnowledgeBaseMapper.class), mock(KnowledgeBaseConverter.class), tools, laterHistory, messageConverter);
        JChatMind runtime = factory.createForSubmission(event.getAgentId(), event.getSessionId(), event.getTriggeringMessageId(),
                event.getUserInput(), event.getHistorySnapshot(), config -> { });
        ChatMemory memory = (ChatMemory) ReflectionTestUtils.getField(runtime, "chatMemory");
        assertNotNull(memory);
        assertEquals(List.of("previous question", "original question"), memory.get("session").stream().map(message -> message.getText()).toList());
        assertEquals("original question", ReflectionTestUtils.getField(runtime, "submittedUserInput"));
        verifyNoInteractions(laterHistory);
        assertThrows(AuthException.class, () -> factory.createForSubmission("agent", "session", "trigger",
                "changed question", event.getHistorySnapshot(), config -> { }));
        assertThrows(AuthException.class, () -> factory.createForSubmission("agent", "session", "another-message",
                "original question", event.getHistorySnapshot(), config -> { }));
    }
}
