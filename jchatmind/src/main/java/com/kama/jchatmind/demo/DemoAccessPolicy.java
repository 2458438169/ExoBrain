package com.kama.jchatmind.demo;

import com.kama.jchatmind.model.dto.AgentDTO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Explicit publication is the only way a resource becomes visible to anonymous visitors. */
@Component
public class DemoAccessPolicy {
    static final Set<String> PUBLIC_TOOL_NAMES = Set.of("KnowledgeTool", "getDate", "directAnswer", "terminate");
    private final DemoProperties properties;

    public DemoAccessPolicy(DemoProperties properties) { this.properties = properties; }

    public String agentId() {
        if (!validId(properties.getAgentId())) throw new DemoException(503, "体验助手暂未配置");
        return UUID.fromString(properties.getAgentId()).toString();
    }

    public List<String> publicKbIds() { return configuredIds(properties.getPublicKbIds()); }

    public List<String> showcaseSessionIds() { return configuredIds(properties.getShowcaseSessionIds()); }

    public boolean isShowcase(String sessionId) {
        return validId(sessionId) && showcaseSessionIds().contains(UUID.fromString(sessionId).toString());
    }

    public boolean isPublicKb(String id) {
        return validId(id) && publicKbIds().contains(UUID.fromString(id).toString());
    }

    public void requirePublicKb(String id) {
        if (!isPublicKb(id)) throw new DemoException(404, "知识库不存在");
    }

    /** Validate and restrict a freshly loaded public runtime DTO; never update the stored agent. */
    public void validateAgent(AgentDTO agent) {
        if (agent == null || !agentId().equals(agent.getId())
                || agent.getAllowedKbs() == null || !agent.getAllowedKbs().stream().allMatch(this::isPublicKb)
                || agent.getChatOptions() == null || agent.getChatOptions().getMessageLength() == null
                || agent.getChatOptions().getMessageLength() < 1 || agent.getChatOptions().getMessageLength() > 100) {
            throw new DemoException(503, "体验助手配置不可用");
        }
        // Admin selections remain in the database. Public requests and the executor each
        // sanitize their own DTO so only the demo executor's fixed tools can be resolved.
        agent.setAllowedTools(List.of());
    }

    static boolean validId(String id) {
        try {
            return id != null && UUID.fromString(id).toString().equalsIgnoreCase(id);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private List<String> configuredIds(List<String> values) {
        if (values == null || values.stream().anyMatch(id -> !validId(id))) {
            throw new DemoException(503, "公开体验资源配置不可用");
        }
        return values.stream().map(id -> UUID.fromString(id).toString()).distinct().toList();
    }
}
