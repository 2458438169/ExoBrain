package com.kama.jchatmind.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.agent.tools.DirectAnswerTool;
import com.kama.jchatmind.agent.tools.DataBaseTools;
import com.kama.jchatmind.agent.tools.TerminateTool;
import com.kama.jchatmind.agent.tools.test.DateTool;
import com.kama.jchatmind.auth.AuthException;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.execution.RunOrigin;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.retrieval.RetrievalService;
import com.kama.jchatmind.runs.RunQueueJob;
import com.kama.jchatmind.runs.RunQueueRepository;
import com.kama.jchatmind.runs.RunQueueStatus;
import com.kama.jchatmind.runs.RunSubmissionException;
import com.kama.jchatmind.runs.RunSubmissionService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.impl.ToolFacadeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 07：游客提交与账号共用同一个持久队列，公开运行时仍只有四个工具。
 *
 * <p>“上一条仍在处理中”来自数据库活动任务（不再是进程内 Set），提交冲突映射为 409；
 * 公开运行时即使数据库里保存了管理员选择的工具，也只解析
 * {@link DemoAccessPolicy#PUBLIC_TOOL_NAMES}。</p>
 */
class DemoExecutionServiceTest {
    private static final String AGENT = "11111111-1111-4111-8111-111111111111";
    private static final String PUBLIC_KB = "44444444-4444-4444-8444-444444444444";
    private static final String PRIVATE_KB = "55555555-5555-4555-8555-555555555555";
    private static final String OWNER = "b".repeat(64);

    private final ChatClientRegistry clients = mock(ChatClientRegistry.class);
    private final SseService sse = mock(SseService.class);
    private final AgentMapper agents = mock(AgentMapper.class);
    private final AgentConverter agentConverter = mock(AgentConverter.class);
    private final KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
    private final KnowledgeBaseConverter knowledgeConverter = mock(KnowledgeBaseConverter.class);
    private final ChatMessageFacadeService messages = mock(ChatMessageFacadeService.class);
    private final ChatMessageConverter messageConverter = mock(ChatMessageConverter.class);
    private final RetrievalService rag = mock(RetrievalService.class);
    private final RunSubmissionService submissions = mock(RunSubmissionService.class);
    private final RunQueueRepository jobs = mock(RunQueueRepository.class);
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
        execution = newExecution(agentConverter);
    }

    private DemoExecutionService newExecution(AgentConverter converter) {
        return new DemoExecutionService(clients, sse, agents, converter, knowledgeBases, knowledgeConverter,
                messages, messageConverter, new DateTool(), new TerminateTool(), new DirectAnswerTool(), rag,
                access, submissions, jobs);
    }

    @Test
    void anActiveQueuedRunIsDetectedFromTheDatabase() {
        assertFalse(execution.isRunning("session"));

        RunQueueJob active = new RunQueueJob("66666666-6666-4666-8666-666666666666",
                "77777777-7777-4777-8777-777777777777", RunOrigin.DEMO, "session", "trigger", AGENT,
                "DEMO:" + OWNER, null, null, null, OWNER, "key", "c".repeat(64), "question", "[]",
                RunQueueStatus.QUEUED, 0, 1, false, null, null, 0L, null, Instant.now(), null, null);
        when(jobs.findActiveBySession("session")).thenReturn(Optional.of(active));
        assertTrue(execution.isRunning("session"), "删除会话前的检查必须来自数据库队列");
    }

    @Test
    void aSessionConflictFromTheQueueIsReportedAs409WithoutPersistingASecondMessage() {
        when(submissions.submitDemo(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RunSubmissionException(409, "SESSION_BUSY", "上一条消息仍在处理中，请等待完成"));

        DemoException conflict = assertThrows(DemoException.class,
                () -> execution.submit(OWNER, AGENT, "session", "question", "key"));
        assertEquals(409, conflict.getStatus());
        verifyNoInteractions(messages);
    }

    @Test
    void demoSubmissionUsesTheSameDurableQueueAndExposesTheRunId() {
        when(submissions.submitDemo(OWNER, AGENT, "session", "question", "key"))
                .thenReturn(new RunSubmissionService.Submission("message", "88888888-8888-4888-8888-888888888888",
                        RunQueueStatus.QUEUED.name(), false));
        CreateChatMessageResponse response = execution.submit(OWNER, AGENT, "session", "question", "key");
        assertEquals("message", response.getChatMessageId());
        assertEquals("88888888-8888-4888-8888-888888888888", response.getRunId());
        assertEquals("QUEUED", response.getRunStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void runtimeRegistersExactlyTheSafeToolCallbacks() {
        when(clients.get("deepseek-chat")).thenReturn(mock(ChatClient.class));
        when(messages.getChatMessagesBySessionIdRecently("session", 10)).thenReturn(List.of());
        JChatMind runtime = execution.runtime(AGENT, "session", access::validateAgent);
        List<ToolCallback> callbacks = (List<ToolCallback>) ReflectionTestUtils.getField(runtime, "availableTools");
        assertNotNull(callbacks);
        assertEquals(DemoAccessPolicy.PUBLIC_TOOL_NAMES,
                callbacks.stream().map(callback -> callback.getToolDefinition().name())
                        .collect(java.util.stream.Collectors.toSet()));
        ToolCallback knowledge = callbacks.stream()
                .filter(callback -> callback.getToolDefinition().name().equals("KnowledgeTool"))
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

        DemoExecutionService isolated = newExecution(realConverter);
        JChatMind publicRuntime = isolated.runtime(AGENT, "session", access::validateAgent);
        List<ToolCallback> publicCallbacks = (List<ToolCallback>) ReflectionTestUtils.getField(publicRuntime, "availableTools");
        assertNotNull(publicCallbacks);
        assertEquals(4, publicCallbacks.size());
        assertEquals(DemoAccessPolicy.PUBLIC_TOOL_NAMES,
                publicCallbacks.stream().map(callback -> callback.getToolDefinition().name())
                        .collect(java.util.stream.Collectors.toSet()));

        JdbcTemplate privateDatabase = mock(JdbcTemplate.class);
        JChatMindFactory managementFactory = new JChatMindFactory(clients, sse, agents, realConverter,
                knowledgeBases, knowledgeConverter,
                new ToolFacadeServiceImpl(List.of(new DataBaseTools(privateDatabase)), false), messages, messageConverter);
        JChatMind managementRuntime = managementFactory.create(AGENT, "session");
        List<ToolCallback> managementCallbacks = (List<ToolCallback>) ReflectionTestUtils.getField(managementRuntime, "availableTools");
        assertNotNull(managementCallbacks);
        assertEquals(List.of("databaseQuery"),
                managementCallbacks.stream().map(callback -> callback.getToolDefinition().name()).toList());
        assertEquals(selectedTools, stored.getAllowedTools());
        assertTrue(realConverter.toDTO(stored).getAllowedTools().contains("dataBaseTool"));
        verify(agents, org.mockito.Mockito.never()).updateById(any());
        verifyNoInteractions(privateDatabase);
    }

    @Test
    void unknownDemoAgentConfigurationIsStillRejectedBeforeExecution() {
        AgentDTO other = AgentDTO.builder().id("99999999-9999-4999-8999-999999999999").allowedKbs(List.of())
                .allowedTools(List.of()).chatOptions(AgentDTO.ChatOptions.defaultOptions()).build();
        DemoException rejected = assertThrows(DemoException.class, () -> access.validateAgent(other));
        assertEquals(503, rejected.getStatus());
    }
}
