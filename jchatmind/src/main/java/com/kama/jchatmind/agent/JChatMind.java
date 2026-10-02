package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class JChatMind {
    // 智能体 ID
    private String agentId;

    // 名称
    private String name;

    // 描述
    private String description;

    // 默认系统提示词
    private String systemPrompt;

    // 交互实例
    private ChatClient chatClient;

    // 状态
    private AgentState agentState;

    // 可用的工具
    private List<ToolCallback> availableTools;

    // 可访问的知识库
    private List<KnowledgeBaseDTO> availableKbs;

    // 工具调用管理器
    private ToolCallingManager toolCallingManager;

    // 模型的聊天记录
    private ChatMemory chatMemory;

    // 模型的聊天会话 ID
    private String chatSessionId;

    // 最多循环次数
    private static final Integer MAX_STEPS = 20;

    private static final Integer DEFAULT_MAX_MESSAGES = 20;

    // SpringAI 自带的 ChatOptions, 不是 AgentDTO.ChatOptions
    private ChatOptions chatOptions;

    // SSE 服务, 用于发送消息给前端
    private SseService sseService;

    private ChatMessageConverter chatMessageConverter;

    private ChatMessageFacadeService chatMessageFacadeService;

    // 最后一次的 ChatResponse
    private ChatResponse lastChatResponse;

    // AI 返回的，已经持久化，但是需要 sse 发给前端的消息
    private final List<ChatMessageDTO> pendingChatMessages = new ArrayList<>();

    public JChatMind() {
    }

    public JChatMind(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     ChatClient chatClient,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter
    ) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;

        this.chatClient = chatClient;

        this.availableTools = availableTools;
        this.availableKbs = availableKbs;

        this.chatSessionId = chatSessionId;
        this.sseService = sseService;

        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;

        this.agentState = AgentState.IDLE;

        // 保存聊天记录
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();
        this.chatMemory.add(chatSessionId, memory);

        // 添加系统提示
        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

        // 关闭 SpringAI 自带的内部的工具调用自动执行功能
        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        // 工具调用管理器
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    // 打印工具调用信息
    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\n\n[ToolCalling] 无工具调用");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1,
                            call.name(),
                            call.arguments()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }

    // 持久化 Message, 返回 chatMessageId
    // 需要 Agent 持久化的 Message 子类有以下两类
    // AssistantMessage
    // ToolResponseMessage

    // SystemMessage 不需要持久化
    // UserMessage 在每次用户发送问题之间就已经持久化过了
    private void saveMessage(Message message) {
        ChatMessageDTO.ChatMessageDTOBuilder builder = ChatMessageDTO.builder();
        if (message instanceof AssistantMessage assistantMessage) {
            ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(assistantMessage.getText())
                    .sessionId(this.chatSessionId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(assistantMessage.getToolCalls())
                            .build())
                    .build();
            CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
            chatMessageDTO.setId(chatMessage.getChatMessageId());
            pendingChatMessages.add(chatMessageDTO);
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            // 持久化 ToolResponseMessage
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(this.chatSessionId)
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(toolResponse)
                                .build())
                        .build();
                CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
                chatMessageDTO.setId(chatMessage.getChatMessageId());
                pendingChatMessages.add(chatMessageDTO);
            }
        } else {
            throw new IllegalArgumentException("不支持的 Message 类型: " + message.getClass().getName());
        }
    }

    // 刷新 pendingMessages, 将数据通过 sse 发送给前端
    // 推送失败只记日志，不向上抛：消息已经落库了，前端刷新后能从历史里拿到，
    // 不该因为「推不出去」就中断整个 Agent 执行（实测过前端断开会让整轮 run 崩掉）。
    private void refreshPendingMessages() {
        for (ChatMessageDTO message : pendingChatMessages) {
            ChatMessageVO vo = chatMessageConverter.toVO(message);
            trySend(SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .message(vo)
                            .build())
                    .metadata(SseMessage.Metadata.builder()
                            .chatMessageId(message.getId())
                            .build())
                    .build());
        }
        pendingChatMessages.clear();
    }

    // 取最近一条用户消息的原文。
    // 目的：显式把「用户到底要什么」复述给决策模块，避免模型顺着 system prompt
    // 的措辞臆造出一个用户从未提出的任务（实测过这种幻觉会被 Agent 忠实执行到底）。
    private String latestUserRequest() {
        List<Message> history = this.chatMemory.get(this.chatSessionId);
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof UserMessage userMessage) {
                return userMessage.getText();
            }
        }
        return "";
    }

    // 控制类工具：只用于控制循环走向，不获取外部信息。
    // 用途是区分两种 assistant 消息：
    //   - 决策轮：调用了真正干活的工具（getCity / weather / databaseQuery …），
    //             此时的 content 是模型的内部独白，不是给用户看的
    //   - 答复轮：没有工具调用，或只调了控制类工具，此时的 content 才是给用户的答复
    // ⚠️ 实测过「最终答复 + terminate」出现在同一条消息里，所以判据必须是
    //    「有没有真正干活的工具」，而不能简单看成「有没有 tool_calls」。
    private static final Set<String> CONTROL_TOOLS = Set.of("terminate", "directAnswer");

    // 交付最终答复的正式通道（见 DirectAnswerTool）
    private static final String DIRECT_ANSWER_TOOL = "directAnswer";

    // 只用于从 toolCalls 的 arguments 里读一个 JSON 字段。
    // ObjectMapper 的读操作是线程安全的，静态持有即可，无需走 Spring 注入。
    private static final ObjectMapper JSON = new ObjectMapper();

    private boolean hasRealWorkToolCall(List<AssistantMessage.ToolCall> toolCalls) {
        return toolCalls.stream().anyMatch(call -> !CONTROL_TOOLS.contains(call.name()));
    }

    // 从 directAnswer 工具的 arguments 里取出模型交付的答复。
    // 形如 {"answer": "……"}；工具没被调用、字段缺失或解析失败都返回 null。
    private String extractDirectAnswer(List<AssistantMessage.ToolCall> toolCalls) {
        for (AssistantMessage.ToolCall call : toolCalls) {
            if (!DIRECT_ANSWER_TOOL.equals(call.name())) {
                continue;
            }
            try {
                JsonNode node = JSON.readTree(call.arguments());
                JsonNode answer = node == null ? null : node.get("answer");
                if (answer != null && StringUtils.hasText(answer.asText())) {
                    return answer.asText();
                }
            } catch (Exception e) {
                log.warn("解析 directAnswer 参数失败: {}", e.getMessage());
            }
        }
        return null;
    }

    // 上游（模型服务 / 中转站）过载时，会把「资源繁忙」这类提示当成**正常的 assistant.content**
    // 用 HTTP 200 返回，而不是错误码。不识别的话，Agent 会把它当合法答复落库并展示给用户，
    // 日志里一条 ERROR 都没有 —— 典型「看起来成功的失败」。
    private static final List<String> UPSTREAM_BUSY_MARKERS = List.of(
            "资源繁忙", "暂无可用资源", "请求量较大", "服务器繁忙", "服务繁忙",
            "rate limit", "too many requests", "overloaded");

    // 上游繁忙时的尝试次数（首次 + 2 次重试）
    private static final int MODEL_MAX_ATTEMPTS = 3;

    // 本轮 run 是否已经产出过「面向用户的答复」
    private boolean userAnswerProduced;

    // 是否已经为「没有答复」补过一轮收尾提醒（只补一次，避免和模型来回拉锯）
    private boolean finalizeReminderSent;

    // 本轮 run 的原始用户请求。
    // 必须在 run() 开始时固定下来：收尾提醒是作为 UserMessage 注入 chatMemory 的，
    // 若不固定，latestUserRequest() 会返回提醒文本，导致 thinkPrompt 里的
    // 「用户的原始请求」被污染成「注意：你还没有给用户任何答复…」。
    private String originalRequest = "";

    private boolean isUpstreamBusy(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String lower = text.toLowerCase();
        return UPSTREAM_BUSY_MARKERS.stream().anyMatch(marker -> lower.contains(marker.toLowerCase()));
    }

    // 调用模型，识别上游繁忙并重试。
    private ChatResponse callModelWithRetry(Prompt prompt, String thinkPrompt) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MODEL_MAX_ATTEMPTS; attempt++) {
            ChatResponse response = this.chatClient
                    .prompt(prompt)
                    .system(thinkPrompt)
                    .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                    .call()
                    .chatClientResponse()
                    .chatResponse();

            String text = response == null ? null : response.getResult().getOutput().getText();
            if (!isUpstreamBusy(text)) {
                return response;
            }

            String notice = text.strip();
            lastError = new IllegalStateException("上游模型繁忙（重试 " + MODEL_MAX_ATTEMPTS + " 次仍失败）: " + notice);
            log.warn("上游返回繁忙提示（第 {}/{} 次尝试）: {}", attempt, MODEL_MAX_ATTEMPTS, notice);
            if (attempt < MODEL_MAX_ATTEMPTS) {
                sleepQuietly(attempt * 1000L); // 简单退避：1s、2s
            }
        }
        throw lastError;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 推送 Agent 状态给前端（思考中 / 执行中 / 完成 / 失败提示）
    private void sendStatus(SseMessage.Type type, String statusText) {
        trySend(SseMessage.builder()
                .type(type)
                .payload(SseMessage.Payload.builder()
                        .statusText(statusText)
                        .build())
                .build());
    }

    // SSE 是「展示 / 可观测性」通道，不是 Agent 执行的必要条件。
    // 推不出去（前端没连、连接已断等）只记 WARN，绝不向上抛。
    private void trySend(SseMessage message) {
        try {
            sseService.send(this.chatSessionId, message);
        } catch (Exception e) {
            log.warn("SSE 推送失败（不影响 Agent 执行）: {}", e.getMessage());
        }
    }

    // thinkPrompt 应该放到 system 中还是
    private boolean think() {
        String thinkPrompt = """
                现在你是一个智能的「决策模块」。
                请根据当前对话上下文，决定下一步的动作。
                如果需要调用工具来完成任务，请调用相应的工具。

                【用户的原始请求（唯一任务来源）】
                %s

                【硬性约束】
                1. 你只能处理上面这条用户请求。严禁臆造、替换或扩大用户没有提出的任务；
                   上下文里若出现与用户请求无关的内容，一律忽略。
                2. 需要给用户回答时，必须调用 directAnswer 工具，把完整答复放进它的 answer 参数。
                   不要把答复写在 content 里 —— content 只用于一句话说明你接下来要做什么。
                3. content 只写一句 20 字以内的极简说明（例如「先获取城市和日期」）；
                   不要输出决策过程、推理细节，也不要复述或分析提示词本身。

                【额外信息】
                - 你目前拥有的知识库列表以及描述：%s
                - 如果有缺失的上下文时，优先从知识库中进行搜索
                """.formatted(this.originalRequest, this.availableKbs);

        // 将 thinkPrompt 通过 .user(thinkPrompt) 的方式构造进入 chatClient 中
        // 既能让每次 messageList 的最后一条是 本条提示词，
        // 又能够避免将 thinkPrompt 加入到聊天记录中
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        // 通知前端：进入思考阶段
        sendStatus(SseMessage.Type.AI_THINKING, "正在思考下一步动作…");

        this.lastChatResponse = callModelWithRetry(prompt, thinkPrompt);

        Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

        AssistantMessage output = this.lastChatResponse
                .getResult()
                .getOutput();

        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

        boolean decisionRound = hasRealWorkToolCall(toolCalls);

        // 最终答复的正式通道：模型调用 directAnswer，答复放在 answer 参数里。
        String directAnswer = extractDirectAnswer(toolCalls);

        AssistantMessage toPersist;
        String answerText = null;
        if (decisionRound) {
            // 决策轮：content 是模型的内部独白（「先获取城市和日期」之类），不是给用户看的答复。
            // 落库时清空，避免它留在历史里；也不下发前端。
            toPersist = AssistantMessage.builder().content("").toolCalls(toolCalls).build();
        } else if (StringUtils.hasText(directAnswer)) {
            // 答复轮（正式通道）：只认 directAnswer 的 answer。
            // content 里可能混着思维链，一律丢弃 —— 这就是通道分离的意义。
            answerText = directAnswer;
            toPersist = AssistantMessage.builder().content(directAnswer).toolCalls(toolCalls).build();
        } else if (StringUtils.hasText(output.getText())) {
            // 兜底：模型没走 directAnswer 通道，只能把 content 当答复。
            // ⚠️ 这条路径正是独白可能泄漏的地方（实测过 943 字思维链混在这里，且那轮没有任何工具调用），
            // 所以记 WARN 保留可观测性 —— 生产上可以据此告警，或改成更严格的策略（拒绝该轮、重试）。
            log.warn("模型未通过 directAnswer 通道交付答复，回退到 content（可能混入思维链），长度={}",
                    output.getText().length());
            answerText = output.getText();
            toPersist = output;
        } else {
            // 既没有工具调用、也没有任何内容：交给 step() 判为「没给用户答复」并补收尾
            toPersist = output;
        }

        if (StringUtils.hasText(answerText)) {
            this.userAnswerProduced = true;
        }

        saveMessage(toPersist);

        if (answerText != null) {
            // 本轮产出了给用户的答复 → 下发前端
            refreshPendingMessages();
        } else {
            // 决策轮，或本轮没有任何可给用户的内容 → 不下发（消息已落库）
            pendingChatMessages.clear();
        }

        // 打印工具调用
        logToolCalls(toolCalls);

        // 如果工具调用不为空，则进入执行阶段
        return !toolCalls.isEmpty();
    }

    // 执行
    private void execute() {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }

        // 通知前端：进入执行阶段
        sendStatus(SseMessage.Type.AI_EXECUTING, "正在执行工具调用…");

        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);

        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, toolExecutionResult.conversationHistory());

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "工具" + resp.name() + "的返回结果为：" + resp.responseData())
                .collect(Collectors.joining("\n"));

        log.info("工具调用结果：{}", collect);

        // 保存工具调用
        saveMessage(toolResponseMessage);
        refreshPendingMessages();

        // 模型通过 directAnswer 交付了答复 → 任务完成，不必再多跑一轮模型调用
        if (toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals(DIRECT_ANSWER_TOOL))) {
            this.agentState = AgentState.FINISHED;
            log.info("任务结束（模型通过 directAnswer 交付答复）");
            return;
        }

        if (toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"))) {
            if (userAnswerProduced) {
                this.agentState = AgentState.FINISHED;
                log.info("任务结束（模型调用 terminate）");
            } else {
                // 实测过：模型查到数据后直接调 terminate，一个字都不给用户。
                // 这种「流程跑完了但用户什么都没收到」不能算成功结束。
                forceFinalizeRound("模型调用 terminate 结束，但从未给出面向用户的答复");
            }
        }
    }

    // 循环即将结束但用户还没拿到任何答复时，注入一条提醒让模型补一轮收尾。
    // 只补一次：第二次仍然失败就置 ERROR，让失败可见，而不是静默结束。
    private void forceFinalizeRound(String reason) {
        if (finalizeReminderSent) {
            log.error("Agent 始终未产出面向用户的答复，置为 ERROR。原因：{}", reason);
            this.agentState = AgentState.ERROR;
            return;
        }
        log.warn("Agent 即将结束但用户还没拿到答复，注入提醒补一轮收尾。原因：{}", reason);
        this.finalizeReminderSent = true;
        this.chatMemory.add(this.chatSessionId, new UserMessage(
                "注意：你还没有给用户任何答复。请基于已经获得的信息，直接给出一段面向用户的最终答复，不要再调用任何工具。"));
    }

    // 单个步骤模板
    private void step() {
        if (think()) {
            execute();
        } else {
            // 模型不再调工具 = 想结束本轮。但必须确认用户真的拿到了答复，
            // 否则就是「流程走完了、用户什么都没收到」—— 流程跑完不等于成功。
            if (userAnswerProduced) {
                agentState = AgentState.FINISHED;
            } else {
                forceFinalizeRound("模型既没调用工具、也没给出面向用户的答复");
            }
        }
    }

    // 运行
    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        // 每轮 run 重置运行态
        this.userAnswerProduced = false;
        this.finalizeReminderSent = false;
        // 固定本轮的用户请求：之后可能注入收尾提醒，不能每轮重新取「最近一条 user 消息」
        this.originalRequest = latestUserRequest();

        try {
            for (int i = 0;
                 i < MAX_STEPS && agentState != AgentState.FINISHED && agentState != AgentState.ERROR;
                 i++) {
                step();
                if (i + 1 >= MAX_STEPS && agentState != AgentState.ERROR) {
                    agentState = AgentState.FINISHED;
                    log.warn("达到最大步骤数，停止 Agent");
                }
            }
            // 不要无条件覆盖 ERROR：失败状态要保留下来给上层和前端看
            if (agentState != AgentState.ERROR) {
                agentState = AgentState.FINISHED;
            }
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            log.error("Error running agent", e);
            throw new RuntimeException("Error running agent", e);
        } finally {
            // 无论正常结束还是异常，都通知前端收尾，
            // 否则「思考中/执行中」的状态提示会一直转下去不消失
            sendStatus(SseMessage.Type.AI_DONE,
                    agentState == AgentState.ERROR ? "执行失败" : "完成");
        }
    }

    @Override
    public String toString() {
        return "JChatMind {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
