package com.kama.jchatmind.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.demo.DemoAccessPolicy;
import com.kama.jchatmind.demo.DemoProperties;
import com.kama.jchatmind.mapper.*;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.*;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.models.ModelCatalogService;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** All HTTP resource operations pass through this service; demo operations have their own policy. */
@Service
public class ResourceAccess {
    public static final String AGENT = "AGENT", KB = "KB", SESSION = "SESSION";
    private final ResourceAclRepository acl;
    private final DemoAccessPolicy publication;
    private final DemoProperties demo;
    private final AgentMapper agents;
    private final AgentConverter converter;
    private final KnowledgeBaseMapper knowledgeBases;
    private final DocumentMapper documents;
    private final ChatSessionMapper sessions;
    private final ChatMessageMapper messages;
    private final AuthService auth;
    private final ModelCatalogService models;
    public ResourceAccess(ResourceAclRepository acl, DemoAccessPolicy publication, DemoProperties demo,
            AgentMapper agents, AgentConverter converter, KnowledgeBaseMapper knowledgeBases,
            DocumentMapper documents, ChatSessionMapper sessions, ChatMessageMapper messages,
            AuthService auth, ModelCatalogService models) {
        this.acl = acl; this.publication = publication; this.demo = demo; this.agents = agents;
        this.converter = converter; this.knowledgeBases = knowledgeBases; this.documents = documents;
        this.sessions = sessions; this.messages = messages; this.auth = auth; this.models = models;
    }
    public boolean canRead(String type, String id) { return canRead(AuthContext.requireUser(), type, id); }
    public boolean canWrite(String type, String id) { return canWrite(AuthContext.requireUser(), type, id); }
    boolean canRead(AuthUser user, String type, String id) {
        if (!isValidId(id)) return false;
        return canWrite(user, type, id) || (KB.equals(type) && publication.isPublicKb(id))
                || (AGENT.equals(type) && id.equalsIgnoreCase(demo.getAgentId()));
    }
    boolean canWrite(AuthUser user, String type, String id) {
        if (!isValidId(id)) return false;
        String resourceId = UUID.fromString(id).toString();
        if (acl.owns(user.id(), type, resourceId)) return true;
        if (!user.isAdmin()) return false;
        // Account/model administration does not grant access to another account's private data.
        if (KB.equals(type)) return publication.isPublicKb(resourceId);
        if (acl.owner(type, resourceId) != null) return false;
        if (SESSION.equals(type)) {
            ChatSession session = sessions.selectById(resourceId);
            if (session != null && session.getMetadata() != null && session.getMetadata().contains("demoOwner"))
                return false;
        }
        return true; // Legacy, unowned system assistants and non-visitor history.
    }
    public boolean isPublicKnowledgeBase(String id) { return publication.isPublicKb(id); }
    public void claim(String type, String id) { acl.claim(AuthContext.requireUser().id(), type, validId(id)); }
    public void remove(String type, String id) { acl.remove(type, validId(id)); }
    public void requireRead(String type, String id) { requireRead(AuthContext.requireUser(), type, id); }
    private void requireRead(AuthUser actor, String type, String id) {
        validId(id);
        if (!canRead(actor, type, id)) throw AuthException.missing();
    }
    public void requireWrite(String type, String id) {
        validId(id);
        if (!canWrite(type, id)) {
            if (canRead(type, id)) throw new AuthException(403, "共享资源为只读");
            throw AuthException.missing();
        }
    }
    public Agent requireAgent(String id, boolean write) {
        if (write) requireWrite(AGENT, id); else requireRead(AGENT, id);
        Agent agent = agents.selectById(validId(id));
        if (agent == null) throw AuthException.missing();
        return agent;
    }
    public KnowledgeBase requireKb(String id, boolean write) {
        if (write) requireWrite(KB, id); else requireRead(KB, id);
        KnowledgeBase kb = knowledgeBases.selectById(validId(id));
        if (kb == null) throw AuthException.missing();
        return kb;
    }
    public Document requireDocument(String id, boolean write) {
        Document document = documents.selectById(validId(id));
        if (document == null) throw AuthException.missing();
        if (write) requireWrite(KB, document.getKbId()); else requireRead(KB, document.getKbId());
        return document;
    }
    public ChatSession requireSession(String id) { return requireSession(AuthContext.requireUser(), id); }
    private ChatSession requireSession(AuthUser actor, String id) {
        requireRead(actor, SESSION, id);
        ChatSession session = sessions.selectById(validId(id));
        if (session == null) throw AuthException.missing();
        return session;
    }
    private ChatSession requireExecutionSession(AuthUser actor, String id) {
        ChatSession session = requireSession(actor, id);
        if (actor.isAdmin()) {
            String owner = acl.owner(SESSION, validId(id));
            // Administrative visibility must never turn another person's editable history into privileged instructions.
            if ((owner != null && !owner.equals(actor.id()))
                    || (owner == null && session.getMetadata() != null && session.getMetadata().contains("demoOwner")))
                throw new AuthException(403, "可以查看和管理此会话，请在自己的会话中使用助手");
        }
        return session;
    }
    public ChatMessage requireMessage(String id, boolean edit) {
        ChatMessage message = messages.selectById(validId(id));
        if (message == null) throw AuthException.missing();
        requireSession(message.getSessionId());
        if (edit && !AuthContext.requireUser().isAdmin() && !"user".equals(message.getRole()))
            throw new AuthException(403, "只能编辑自己的提问");
        return message;
    }
    public void validateAgentConfiguration(AgentDTO config) {
        validateAgentConfiguration(AuthContext.requireUser(), config, false);
    }
    private void validateAgentConfiguration(AuthUser actor, AgentDTO config, boolean publicRuntime) {
        if (config == null) throw new AuthException(400, "助手配置不能为空");
        text(config.getName(), "助手名称", 100, true);
        text(config.getDescription(), "助手简介", 2000, false);
        text(config.getSystemPrompt(), "助手提示词", 20000, false);
        if (config.getAllowedKbs() == null || config.getAllowedKbs().size() > 50
                || config.getAllowedTools() == null || config.getAllowedTools().size() > 50)
            throw new AuthException(400, "助手的知识库或工具配置无效");
        for (String kbId : config.getAllowedKbs()) {
            requireRead(actor, KB, kbId);
            if (knowledgeBases.selectById(validId(kbId)) == null) throw AuthException.missing();
        }
        if (!actor.isAdmin()) {
            if (publicRuntime) config.setAllowedTools(List.of());
            else if (!config.getAllowedTools().isEmpty()) throw new AuthException(403, "普通用户不能使用管理工具");
        }
        if (config.getAllowedTools().stream().anyMatch(name -> "dataBaseTool".equals(name) || "fileSystemTool".equals(name)))
            throw new AuthException(403, "数据库和文件系统工具未提供账号隔离，不能在账号工作区启用");
        var options = config.getChatOptions();
        if (options == null || options.getMessageLength() == null || options.getMessageLength() < 1
                || options.getMessageLength() > 100 || options.getTemperature() == null
                || !Double.isFinite(options.getTemperature()) || options.getTemperature() < 0 || options.getTemperature() > 2
                || options.getTopP() == null || !Double.isFinite(options.getTopP()) || options.getTopP() <= 0 || options.getTopP() > 1)
            throw new AuthException(400, "助手模型参数无效");
        models.validateLlmSelection(config.getModel());
    }
    public void validateNewSession(String agentId, String title) {
        Agent agent = requireAgent(agentId, false);
        text(title, "对话标题", 200, false);
        try { validateAgentConfiguration(AuthContext.requireUser(), converter.toDTO(agent),
                !canWrite(AGENT, agentId)); }
        catch (JsonProcessingException error) { throw new AuthException(400, "助手配置不可用"); }
    }
    public void validateChatMessage(CreateChatMessageRequest request) {
        ChatSession session = requireExecutionSession(AuthContext.requireUser(), request.getSessionId());
        if (!Objects.equals(session.getAgentId(), request.getAgentId())) throw new AuthException(400, "会话与助手不匹配");
        if (request.getRole() != ChatMessageDTO.RoleType.USER || request.getMetadata() != null)
            throw new AuthException(400, "只能提交用户消息，不能指定工具或系统元数据");
        text(request.getContent(), "消息", 16000, true);
        validateNewSession(request.getAgentId(), null);
    }
    /** Also guards error notifications when no usable runtime configuration was constructed. */
    public AuthUser requireExecutionActor(String userId, int authVersion, String tokenHash, String sessionId, String agentId) {
        AuthUser actor = auth.currentActor(userId, authVersion, tokenHash);
        ChatSession session = requireExecutionSession(actor, sessionId);
        if (!Objects.equals(session.getAgentId(), agentId)) throw AuthException.missing();
        requireRead(actor, AGENT, agentId);
        return actor;
    }
    /** Called on executor threads and before every tool invocation, without ThreadLocal authority. */
    public void validateRuntime(String userId, int authVersion, String tokenHash, String sessionId, AgentDTO config) {
        if (config == null) throw AuthException.missing();
        AuthUser actor = requireExecutionActor(userId, authVersion, tokenHash, sessionId, config.getId());
        validateAgentConfiguration(actor, config, !canWrite(actor, AGENT, config.getId()));
    }
    public static void text(String value, String label, int maxLength, boolean required) {
        if (required && (value == null || value.isBlank())) throw new AuthException(400, label + "不能为空");
        if (value != null && value.length() > maxLength) throw new AuthException(400, label + "过长");
    }
    public static String validId(String id) {
        if (!isValidId(id)) throw AuthException.missing();
        return UUID.fromString(id).toString();
    }
    private static boolean isValidId(String id) {
        try { return id != null && UUID.fromString(id).toString().equalsIgnoreCase(id); }
        catch (IllegalArgumentException error) { return false; }
    }
}
