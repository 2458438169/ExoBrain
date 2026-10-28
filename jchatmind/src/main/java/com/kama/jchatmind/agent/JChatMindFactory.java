package com.kama.jchatmind.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.auth.ScopedToolCallback;
import com.kama.jchatmind.auth.AuthException;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolFacadeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JChatMindFactory {

    private static final Logger log = LoggerFactory.getLogger(JChatMindFactory.class);
    private final ChatClientRegistry chatClientRegistry;
    private final SseService sseService;
    private final AgentMapper agentMapper;
    private final AgentConverter agentConverter;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final ToolFacadeService toolFacadeService;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;

    public JChatMindFactory(
            ChatClientRegistry chatClientRegistry,
            SseService sseService,
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            ToolFacadeService toolFacadeService,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.sseService = sseService;
        this.agentMapper = agentMapper;
        this.agentConverter = agentConverter;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseConverter = knowledgeBaseConverter;
        this.toolFacadeService = toolFacadeService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
    }

    private Agent loadAgent(String agentId) {
        return agentMapper.selectById(agentId);
    }

    /**
    /**
     * 读取数据库里的历史并投影成模型输入；投影结果与逐条理由都留下可核对记录。
     */
    private List<Message> loadMemory(String chatSessionId, AgentDTO agentConfig) {
        int messageLength = agentConfig.getChatOptions().getMessageLength();
        List<ChatMessageDTO> chatMessages =
                chatMessageFacadeService.getChatMessagesBySessionIdRecently(chatSessionId, messageLength);
        return logAndUnwrap(chatSessionId, null, chatMessages);
    }

    private List<Message> logAndUnwrap(String chatSessionId, String triggeringMessageId,
                                       List<ChatMessageDTO> chatMessages) {
        MemoryProjection projection = project(chatMessages);
        if (log.isInfoEnabled()) {
            log.info("model input projection: session={} trigger={} dbHistory={} projected={} decisions={}",
                    chatSessionId, triggeringMessageId, chatMessages.size(), projection.messages().size(),
                    projection.decisions().stream()
                            .map(decision -> decision.messageId() + ":" + decision.role()
                                    + ":r" + decision.round() + ":" + decision.reason())
                            .collect(java.util.stream.Collectors.joining(",")));
        }
        return projection.messages();
    }

    /**
     * 输入投影的逐条决策记录，供公开测试核对「模型实际看到了什么、为什么」。
     *
     * <p>只记消息 ID、角色、轮次与理由，不记正文内容，也不记访客私有内容。</p>
     */
    record Decision(String messageId, String role, int round, boolean included, String reason) {
    }

    record MemoryProjection(List<Message> messages, List<Decision> decisions) {
    }

    /**
     * 把数据库里的消息投影成「本轮给模型看的输入」。
     *
     * <p>按轮次组织：一条用户消息开启一轮，直到下一条用户消息为止。规则是：</p>
     * <ul>
     *   <li><b>本轮</b>（最后一条用户消息）是唯一执行意图；</li>
     *   <li><b>已答过的历史轮</b>整轮保留，工具调用与工具响应照原样配对；</li>
     *   <li><b>没答上的旧轮</b>（失败、取消、中断都不会写回答）只保留那条用户消息，作为被动上下文——
     *       它让「把刚才的问题再答一次」这类重试仍能理解原问题，但不会被执行；</li>
     *   <li>旧轮里没有配对回答的助手决策与工具结果一律丢弃，避免旧来源被当成本轮登记的来源。</li>
     * </ul>
     *
     * <p>只要存在被动上下文，就会在历史与本轮之间插入一条系统说明，明确「历史里未完成的问题不是本轮待办」。</p>
     */
    static MemoryProjection project(List<ChatMessageDTO> chatMessages) {
        List<Message> memory = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        List<SystemMessage> systemMessages = new ArrayList<>();
        List<int[]> rounds = new ArrayList<>();
        int roundStart = -1;
        for (int index = 0; index < chatMessages.size(); index++) {
            if (chatMessages.get(index).getRole() == ChatMessageDTO.RoleType.USER) {
                if (roundStart >= 0) {
                    rounds.add(new int[]{roundStart, index});
                }
                roundStart = index;
            }
        }
        if (roundStart >= 0) {
            rounds.add(new int[]{roundStart, chatMessages.size()});
        }
        boolean hasPassiveContext = false;
        for (int roundIndex = 0; roundIndex < rounds.size(); roundIndex++) {
            int[] range = rounds.get(roundIndex);
            boolean current = roundIndex == rounds.size() - 1;
            boolean answered = answeredWithin(chatMessages, range[0], range[1]);
            ChatMessageDTO user = chatMessages.get(range[0]);
            if (!StringUtils.hasLength(user.getContent())) {
                decisions.add(new Decision(user.getId(), "user", roundIndex, false, "blank"));
                continue;
            }
            if (current) {
                memory.add(new UserMessage(user.getContent()));
                decisions.add(new Decision(user.getId(), "user", roundIndex, true, "current-task"));
                appendAnsweredTail(chatMessages, range, roundIndex, memory, decisions);
                continue;
            }
            if (answered) {
                memory.add(new UserMessage(user.getContent()));
                decisions.add(new Decision(user.getId(), "user", roundIndex, true, "answered-history"));
                appendAnsweredTail(chatMessages, range, roundIndex, memory, decisions);
            } else {
                memory.add(new UserMessage(user.getContent()));
                decisions.add(new Decision(user.getId(), "user", roundIndex, true, "previous-unanswered:context"));
                hasPassiveContext = true;
                for (int index = range[0] + 1; index < range[1]; index++) {
                    ChatMessageDTO skipped = chatMessages.get(index);
                    decisions.add(new Decision(skipped.getId(), skipped.getRole().getRole(), roundIndex, false,
                            "unanswered-round:dropped-with-tools"));
                }
            }
        }
        for (ChatMessageDTO chatMessageDTO : chatMessages) {
            if (chatMessageDTO.getRole() == ChatMessageDTO.RoleType.SYSTEM
                    && StringUtils.hasLength(chatMessageDTO.getContent())) {
                systemMessages.add(new SystemMessage(chatMessageDTO.getContent()));
            }
        }
        if (hasPassiveContext) {
            int insertAt = memory.size();
            for (int index = memory.size() - 1; index >= 1; index--) {
                if (memory.get(index) instanceof UserMessage) {
                    insertAt = index;
                    break;
                }
            }
            memory.add(insertAt, new SystemMessage(PASSIVE_CONTEXT_NOTE));
        }
        memory.addAll(0, systemMessages);
        return new MemoryProjection(memory, decisions);
    }

    /** 兼容旧调用点：只要消息列表。 */
    static List<Message> restoreMemory(List<ChatMessageDTO> chatMessages) {
        return project(chatMessages).messages();
    }

    private static void appendAnsweredTail(List<ChatMessageDTO> chatMessages, int[] range, int roundIndex,
                                           List<Message> memory, List<Decision> decisions) {
        for (int index = range[0] + 1; index < range[1]; index++) {
            ChatMessageDTO chatMessageDTO = chatMessages.get(index);
            switch (chatMessageDTO.getRole()) {
                case ASSISTANT:
                    memory.add(AssistantMessage.builder()
                            .content(chatMessageDTO.getContent())
                            .toolCalls(chatMessageDTO.getMetadata() == null
                                    || chatMessageDTO.getMetadata().getToolCalls() == null
                                    ? List.of()
                                    : chatMessageDTO.getMetadata().getToolCalls())
                            .build());
                    decisions.add(new Decision(chatMessageDTO.getId(), "assistant", roundIndex, true,
                            "answered-history"));
                    break;
                case TOOL:
                    memory.add(ToolResponseMessage.builder()
                            .responses(List.of(chatMessageDTO.getMetadata().getToolResponse()))
                            .build());
                    decisions.add(new Decision(chatMessageDTO.getId(), "tool", roundIndex, true, "answered-history"));
                    break;
                case USER:
                case SYSTEM:
                    break;
                default:
                    log.error("不支持的 Message 类型: {}, content = {}",
                            chatMessageDTO.getRole().getRole(), chatMessageDTO.getContent());
                    throw new IllegalStateException("不支持的 Message 类型");
            }
        }
    }

    /** 这一轮里是否存在带正文的助手消息：有就是答上了。 */
    private static boolean answeredWithin(List<ChatMessageDTO> messages, int start, int end) {
        for (int index = start + 1; index < end; index++) {
            ChatMessageDTO message = messages.get(index);
            if (message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                    && StringUtils.hasLength(message.getContent())) {
                return true;
            }
        }
        return false;
    }

    /** 历史里未答上的问题只是上下文，不是本轮待办；显式重答时用它理解指代。 */
    private static final String PASSIVE_CONTEXT_NOTE =
            "历史里可能有未被回答的用户提问（失败、取消或中断）。它们只是上下文，用来理解指代"
                    + "（例如「刚才那个问题」「继续」）；不是本轮待办，不要代替本轮任务去执行。"
                    + "本轮只执行最后一条用户消息；只有当它明确要求重答或继续上一条时，才回答那条问题。";

    private AgentDTO toAgentConfig(Agent agent) {
        try {
            return agentConverter.toDTO(agent);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析 Agent 配置失败", e);
        }
    }

    private List<KnowledgeBaseDTO> resolveRuntimeKnowledgeBases(AgentDTO agentConfig) {
        List<String> allowedKbIds = agentConfig.getAllowedKbs();
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectByIdBatch(allowedKbIds);
        if (knowledgeBases.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeBaseDTO> kbDTOs = new ArrayList<>();
        try {
            for (KnowledgeBase knowledgeBase : knowledgeBases) {
                KnowledgeBaseDTO kbDTO = knowledgeBaseConverter.toDTO(knowledgeBase);
                kbDTOs.add(kbDTO);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return kbDTOs;
    }

    private List<Tool> resolveRuntimeTools(AgentDTO agentConfig) {
        // 固定工具（系统强制）
        List<Tool> runtimeTools = new ArrayList<>(toolFacadeService.getFixedTools());

        // 可选工具（按 Agent 配置）
        List<String> allowedToolNames = agentConfig.getAllowedTools();
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return runtimeTools;
        }

        Map<String, Tool> optionalToolMap = toolFacadeService.getOptionalTools()
                .stream()
                .collect(Collectors.toMap(Tool::getName, Function.identity()));

        for (String toolName : allowedToolNames) {
            Tool tool = optionalToolMap.get(toolName);
            if (tool != null) {
                runtimeTools.add(tool);
            }
        }
        return runtimeTools;
    }

    private List<ToolCallback> buildToolCallbacks(List<Tool> runtimeTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : runtimeTools) {
            Object target = resolveToolTarget(tool);
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(target)
                    .build()
                    .getToolCallbacks();
            callbacks.addAll(Arrays.asList(toolCallbacks));
        }
        return callbacks;
    }

    private Object resolveToolTarget(Tool tool) {
        try {
            return AopUtils.isAopProxy(tool)
                    ? AopUtils.getTargetClass(tool)
                    : tool;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "解析工具目标对象失败: " + tool.getName(), e);
        }
    }

    private JChatMind buildAgentRuntime(
            Agent agent,
            AgentDTO agentConfig,
            List<Message> memory,
            List<KnowledgeBaseDTO> knowledgeBases,
            List<ToolCallback> toolCallbacks,
            String chatSessionId,
            Runnable runtimeGuard,
            String submittedUserInput
    ) {
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (Objects.isNull(chatClient)) {
            throw new IllegalStateException("未找到对应的 ChatClient: " + agent.getModel());
        }
        return new JChatMind(
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                agent.getSystemPrompt(),
                chatClient,
                agentConfig.getChatOptions().getMessageLength(),
                memory,
                toolCallbacks,
                knowledgeBases,
                chatSessionId,
                sseService,
                chatMessageFacadeService,
                chatMessageConverter,
                runtimeGuard,
                submittedUserInput
        );
    }

    /**
     * 创建一个 JChatMind 实例
     */
    public JChatMind create(String agentId, String chatSessionId) {
        return create(agentId, chatSessionId, config -> { });
    }

    /** Validate the exact runtime configuration before resolving any data or tools. */
    public JChatMind create(String agentId, String chatSessionId, Consumer<AgentDTO> validateConfig) {
        return createRuntime(agentId, chatSessionId, validateConfig, null, null);
    }

    /** Account submissions carry an immutable history snapshot and the exact triggering question. */
    public JChatMind createForSubmission(String agentId, String chatSessionId, String triggeringMessageId,
            String userInput, String historySnapshot, Consumer<AgentDTO> validateConfig) {
        List<ChatMessageDTO> snapshot;
        try {
            snapshot = new ObjectMapper().findAndRegisterModules()
                    .readValue(historySnapshot, new TypeReference<>() { });
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new AuthException(400, "本次会话的历史快照无效");
        }
        if (snapshot == null || snapshot.isEmpty() || snapshot.size() > 101
                || snapshot.stream().anyMatch(message -> message == null || !chatSessionId.equals(message.getSessionId())))
            throw new AuthException(400, "本次会话的历史快照无效");
        var trigger = snapshot.getLast();
        if (trigger.getRole() != ChatMessageDTO.RoleType.USER || !Objects.equals(triggeringMessageId, trigger.getId())
                || !Objects.equals(userInput, trigger.getContent())) throw new AuthException(400, "触发消息与本次请求不一致");
        return createRuntime(agentId, chatSessionId, validateConfig, snapshot, userInput);
    }

    private JChatMind createRuntime(String agentId, String chatSessionId, Consumer<AgentDTO> validateConfig,
            List<ChatMessageDTO> capturedHistory, String submittedUserInput) {
        Agent agent = loadAgent(agentId);
        if (agent == null) throw new IllegalStateException("Agent is not configured");
        AgentDTO agentConfig = toAgentConfig(agent);
        validateConfig.accept(agentConfig);
        List<Message> memory = capturedHistory == null
                ? loadMemory(chatSessionId, agentConfig)
                : logAndUnwrap(chatSessionId, capturedHistory.getLast().getId(),
                        capturedHistory.subList(Math.max(0, capturedHistory.size()
                                - agentConfig.getChatOptions().getMessageLength()), capturedHistory.size()));
        Runnable runtimeGuard = () -> validateConfig.accept(agentConfig);

        // 解析 agent 的支持的知识库
        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(agentConfig);
        // 解析 agent 支持的工具调用
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig);
        // 将工具调用转换成 ToolCallback 的形式
        Set<String> allowedKbIds = agentConfig.getAllowedKbs() == null ? Set.of()
                : new HashSet<>(agentConfig.getAllowedKbs());
        List<ToolCallback> toolCallbacks = buildToolCallbacks(runtimeTools).stream()
                .map(callback -> (ToolCallback) new ScopedToolCallback(callback, allowedKbIds,
                        runtimeGuard)).toList();

        return buildAgentRuntime(
                agent,
                agentConfig,
                memory,
                knowledgeBases,
                toolCallbacks,
                chatSessionId,
                runtimeGuard,
                submittedUserInput
        );
    }
}
