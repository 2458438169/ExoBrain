package com.kama.jchatmind.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import java.util.Set;
import java.util.Locale;
import java.util.stream.Collectors;

/** A model-supplied KB identifier is untrusted; scope it before the underlying RAG tool can run. */
public final class ScopedToolCallback implements ToolCallback {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ToolCallback delegate;
    private final Set<String> allowedKbs;
    private final Runnable authorize;
    public ScopedToolCallback(ToolCallback delegate, Set<String> allowedKbs, Runnable authorize) {
        this.delegate = delegate;
        this.allowedKbs = allowedKbs.stream().map(id -> id.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        this.authorize = authorize;
    }
    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
    @Override public String call(String input) { validate(input); return delegate.call(input); }
    @Override public String call(String input, ToolContext context) { validate(input); return delegate.call(input, context); }
    private void validate(String input) {
        authorize.run();
        if (!"KnowledgeTool".equals(getToolDefinition().name())) return;
        try {
            JsonNode arguments = JSON.readTree(input);
            if (arguments == null || !arguments.hasNonNull("kbsId") || !arguments.get("kbsId").isTextual()
                    || !allowedKbs.contains(ResourceAccess.validId(arguments.get("kbsId").asText())))
                throw AuthException.forbidden();
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException error) {
            throw new AuthException(400, "知识库工具参数无效");
        }
    }
}
