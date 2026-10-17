package com.kama.jchatmind.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.auth.AuthException;
import com.kama.jchatmind.agent.tools.DirectAnswerTool;
import com.kama.jchatmind.agent.tools.DataBaseTools;
import com.kama.jchatmind.agent.tools.TerminateTool;
import com.kama.jchatmind.agent.tools.test.DateTool;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.retrieval.RetrievalService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.impl.ToolFacadeServiceImpl;
import com.kama.jchatmind.execution.RunLifecycleService;
import com.kama.jchatmind.execution.RunRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoExecutionServiceTest {
    private static final String AGENT = "11111111-1111-4111-8111-111111111111";
    private static final String PUBLIC_KB = "44444444-4444-4444-8444-444444444444";
    private static final String PRIVATE_KB = "55555555-5555-4555-8555-555555555555";
    private final ChatClientRegistry clients = mock(ChatClientRegistry.class);
    private final SseService sse = mock(SseService.class);
    private final AgentMapper agents = mock(AgentMapper.class);
    private final AgentConverter agentConverter = mock(AgentConverter.class);
    private final KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
    private final KnowledgeBaseConverter knowledgeConverter = mock(KnowledgeBaseConverter.class);
    private final ChatMessageFacadeService messages = mock(ChatMessageFacadeService.class);
    private final ChatMessageConverter messageConverter = mock(ChatMessageConverter.class);
    private final RetrievalService rag = mock(RetrievalService.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final RunLifecycleService runs = mock(RunLifecycleService.class);
    private DemoAccessPolicy access;
    private DemoExecutionService execution;
    private AgentDTO config;

    @BeforeEach
    void setUp() throws Exception {
        DemoProperties properties = new DemoProperties();
        properties.setAgentId(AGENT);
        properties.setPublicKbIds(List.of(PUBLIC_KB));
        access = new DemoAccessPolicy(properties);
        Agent entity = Agent.builder().id(AGENT).model("deepseek-chat").build();
        config = AgentDTO.builder().id(AGENT).allowedKbs(List.of()).allowedTools(List.of())
                .chatOptions(AgentDTO.ChatOptions.defaultOptions()).build();
        when(agents.selectById(AGENT)).thenReturn(entity);
        when(agentConverter.toDTO(entity)).thenReturn(config);
        when(runs.startDemoRun(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                new RunLifecycleService.RunStart("run-" + invocation.getArgument(1), RunRecorder.noop("run"),
                        Instant.now(), Instant.now(), System.nanoTime()));
        execution = new DemoExecutionService(clients, sse, agents, agentConverter, knowledgeBases, knowledgeConverter,
                messages, messageConverter, new DateTool(), new TerminateTool(), new DirectAnswerTool(), rag, access, queued::add, runs);
    }

    @Test
    void configChangesBetweenHttpValidationAndExecutionFailBeforePrivateDataLoads() {
        execution.submit(AGENT, "session", () -> CreateChatMessageResponse.builder().chatMessageId("message").build());
        assertTrue(execution.isRunning("session"));
        config.setAllowedKbs(List.of(PRIVATE_KB));
        queued.getFirst().run();
        assertFalse(execution.isRunning("session"));
        verifyNoInteractions(knowledgeBases, knowledgeConverter, messages, clients, rag);
        verify(sse).send(eq("session"), argThat(event -> "执行失败，请稍后重试".equals(event.getPayload().getStatusText())));
    }

    @Test
    void duplicateRequestsNeverPersistASecondMessageAndFailuresReleaseTheSession() {
        Supplier<CreateChatMessageResponse> persist = mock(Supplier.class);
        when(persist.get()).thenReturn(CreateChatMessageResponse.builder().chatMessageId("first").build());
        execution.submit(AGENT, "session", persist);
        DemoException duplicate = assertThrows(DemoException.class, () -> execution.submit(AGENT, "session", persist));
        assertEquals(409, duplicate.getStatus());
        verify(persist, times(1)).get();
        config.setAllowedKbs(List.of(PRIVATE_KB));
        queued.getFirst().run();
        assertFalse(execution.isRunning("session"));

        assertThrows(IllegalStateException.class, () -> execution.submit(AGENT, "failed", () -> {
            throw new IllegalStateException("persistence failed");
        }));
        assertFalse(execution.isRunning("failed"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runtimeRegistersExactlyTheSafeToolCallbacks() {
        when(clients.get("deepseek-chat")).thenReturn(mock(ChatClient.class));
        when(messages.getChatMessagesBySessionIdRecently("session", 10)).thenReturn(List.of());
        JChatMindFactory factory = (JChatMindFactory) ReflectionTestUtils.getField(execution, "factory");
        assertNotNull(factory);
        JChatMind runtime = factory.create(AGENT, "session", access::validateAgent);
        List<ToolCallback> callbacks = (List<ToolCallback>) ReflectionTestUtils.getField(runtime, "availableTools");
        assertNotNull(callbacks);
        assertEquals(DemoAccessPolicy.PUBLIC_TOOL_NAMES,
                callbacks.stream().map(callback -> callback.getToolDefinition().name()).collect(java.util.stream.Collectors.toSet()));
        ToolCallback knowledge = callbacks.stream().filter(callback -> callback.getToolDefinition().name().equals("KnowledgeTool"))
                .findFirst().orElseThrow();
        AuthException blocked = assertThrows(AuthException.class,
                () -> knowledge.call("{\"kbsId\":\"" + PRIVATE_KB + "\",\"query\":\"private\"}"));
        assertEquals(403, blocked.getStatus());
        verifyNoInteractions(knowledgeBases, rag);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicRuntimeIgnoresStoredPrivateToolNamesWhileManagementRuntimeKeepsItsSelections() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AgentConverter realConverter = new AgentConverter(json);
        String selectedTools = "[\"dataBaseTool\",\"emailTool\",\"KnowledgeTool\",\"getDate\",\"untrusted::tool\"]";
        Agent stored = Agent.builder().id(AGENT).model("deepseek-chat").allowedTools(selectedTools)
                .allowedKbs("[]").chatOptions(json.writeValueAsString(AgentDTO.ChatOptions.defaultOptions())).build();
        when(agents.selectById(AGENT)).thenReturn(stored);
        when(clients.get("deepseek-chat")).thenReturn(mock(ChatClient.class));
        when(messages.getChatMessagesBySessionIdRecently("session", 10)).thenReturn(List.of());

        DemoExecutionService isolated = new DemoExecutionService(clients, sse, agents, realConverter,
                knowledgeBases, knowledgeConverter, messages, messageConverter, new DateTool(),
                new TerminateTool(), new DirectAnswerTool(), rag, access, queued::add, runs);
        JChatMindFactory publicFactory = (JChatMindFactory) ReflectionTestUtils.getField(isolated, "factory");
        assertNotNull(publicFactory);
        JChatMind publicRuntime = publicFactory.create(AGENT, "session", access::validateAgent);
        List<ToolCallback> publicCallbacks = (List<ToolCallback>) ReflectionTestUtils.getField(publicRuntime, "availableTools");
        assertNotNull(publicCallbacks);
        assertEquals(4, publicCallbacks.size());
        assertEquals(DemoAccessPolicy.PUBLIC_TOOL_NAMES,
                publicCallbacks.stream().map(callback -> callback.getToolDefinition().name())
                        .collect(java.util.stream.Collectors.toSet()));

        JdbcTemplate privateDatabase = mock(JdbcTemplate.class);
        JChatMindFactory managementFactory = new JChatMindFactory(clients, sse, agents, realConverter,
                knowledgeBases, knowledgeConverter, new ToolFacadeServiceImpl(List.of(new DataBaseTools(privateDatabase))),
                messages, messageConverter);
        JChatMind managementRuntime = managementFactory.create(AGENT, "session");
        List<ToolCallback> managementCallbacks = (List<ToolCallback>) ReflectionTestUtils.getField(managementRuntime, "availableTools");
        assertNotNull(managementCallbacks);
        assertEquals(List.of("databaseQuery"), managementCallbacks.stream().map(callback -> callback.getToolDefinition().name()).toList());
        assertEquals(selectedTools, stored.getAllowedTools());
        assertTrue(realConverter.toDTO(stored).getAllowedTools().contains("dataBaseTool"));
        verify(agents, never()).updateById(any());
        verify(agents, never()).insert(any());
        verify(agents, never()).deleteById(anyString());
        verifyNoInteractions(privateDatabase, rag, knowledgeBases);
    }
}
