package com.kama.jchatmind.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.response.*;
import com.kama.jchatmind.model.vo.AgentVO;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Arrays;
import java.util.UUID;

@Service
@AllArgsConstructor
public class DemoChatService {
    private final DemoAccessPolicy access;
    private final DemoSessionRepository sessions;
    private final AgentMapper agents;
    private final AgentConverter agentConverter;
    private final ChatMessageFacadeService messages;
    private final SseService sse;
    private final DemoExecutionService execution;
    private final DemoRateLimiter limiter;

    public GetAgentsResponse agents() {
        AgentDTO agent = demoAgent();
        AgentVO publicAgent = AgentVO.builder().id(agent.getId()).name(agent.getName())
                .description(agent.getDescription()).model(agent.getModel())
                .allowedTools(List.of()).allowedKbs(agent.getAllowedKbs()).readOnly(true).build();
        return GetAgentsResponse.builder().agents(new AgentVO[]{publicAgent}).build();
    }

    public GetChatSessionsResponse sessions(String owner) {
        return GetChatSessionsResponse.builder()
                .chatSessions(sessions.list(agentId(), owner).toArray(new ChatSessionVO[0])).build();
    }

    public GetChatSessionsResponse sessionsForAgent(String owner, String agentId) {
        requireAgent(agentId);
        return sessions(owner);
    }

    public GetChatSessionResponse session(String owner, String sessionId) {
        return GetChatSessionResponse.builder().chatSession(requireOwned(owner, sessionId)).build();
    }

    @Transactional
    public CreateChatSessionResponse create(String owner, String requestedAgentId, String title) {
        requireAgent(requestedAgentId);
        demoAgent();
        String safeTitle = title(title);
        sessions.lockOwner(owner);
        if (sessions.list(agentId(), owner).size() >= 20) throw new DemoException(429, "每位访客最多保留 20 个会话，请先删除旧会话");
        String id = UUID.randomUUID().toString();
        sessions.insert(id, agentId(), safeTitle, owner);
        return CreateChatSessionResponse.builder().chatSessionId(id).build();
    }

    public void rename(String owner, String sessionId, String title) {
        requireOwned(owner, sessionId);
        requireEditable(sessionId);
        if (!sessions.rename(sessionId, agentId(), owner, title(title))) throw missing();
    }

    public void delete(String owner, String sessionId) {
        requireOwned(owner, sessionId);
        requireEditable(sessionId);
        if (execution.isRunning(sessionId)) throw new DemoException(409, "消息正在处理中，请完成后再删除会话");
        if (!sessions.delete(sessionId, agentId(), owner)) throw missing();
    }

    public GetChatMessagesResponse messages(String owner, String sessionId) {
        requireOwned(owner, sessionId);
        return GetChatMessagesResponse.builder().chatMessages(
                Arrays.stream(messages.getChatMessagesBySessionId(sessionId).getChatMessages())
                        .filter(message -> message.getRole() != ChatMessageDTO.RoleType.SYSTEM)
                        .toArray(com.kama.jchatmind.model.vo.ChatMessageVO[]::new)).build();
    }

    public CreateChatMessageResponse send(String owner, String requestedAgentId, String sessionId,
                                          String role, String content, String idempotencyKey) {
        requireAgent(requestedAgentId);
        requireOwned(owner, sessionId);
        requireEditable(sessionId);
        if (!"user".equals(role)) throw new DemoException(403, "体验模式只允许发送用户消息");
        if (content == null || content.isBlank() || content.length() > 4000) {
            throw new DemoException(400, "消息不能为空，且最多 4000 个字符");
        }
        demoAgent();
        limiter.acquire(owner);
        // 07：与账号共用同一个持久队列（消息 + 固定快照 + 运行 + 任务同一事务）；
        // 同会话的“上一条仍在处理中”由数据库唯一活动约束保证，不再依赖进程内 Set。
        return execution.submit(owner, agentId(), sessionId, content.strip(), idempotencyKey);
    }

    public SseEmitter connect(String owner, String sessionId) {
        requireOwned(owner, sessionId);
        requireEditable(sessionId);
        return sse.connect(sessionId);
    }

    private void requireEditable(String sessionId) {
        if (access.isShowcase(sessionId)) throw new DemoException(403, "精选示例为只读记录，请创建自己的体验会话");
    }

    private ChatSessionVO requireOwned(String owner, String sessionId) {
        validId(sessionId);
        ChatSessionVO session = sessions.find(sessionId, agentId(), owner);
        if (session == null) throw missing();
        return session;
    }

    private AgentDTO demoAgent() {
        Agent agent = agents.selectById(agentId());
        if (agent == null) throw new DemoException(503, "体验助手暂未配置");
        try {
            AgentDTO dto = agentConverter.toDTO(agent);
            access.validateAgent(dto);
            return dto;
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new DemoException(503, "体验助手配置不可用");
        }
    }

    private String agentId() {
        return access.agentId();
    }

    private void requireAgent(String requestedAgentId) {
        if (!agentId().equals(requestedAgentId)) throw new DemoException(403, "体验模式只能使用公开助手");
    }

    private static void validId(String id) {
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id)) throw missing();
        } catch (IllegalArgumentException error) { throw missing(); }
    }

    private static String title(String title) {
        if (title == null || title.isBlank()) return "新会话";
        if (title.length() > 120) throw new DemoException(400, "会话标题最多 120 个字符");
        return title.strip();
    }

    private static DemoException missing() { return new DemoException(404, "会话不存在"); }
}
