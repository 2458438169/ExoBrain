package com.kama.jchatmind.demo;

import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import com.kama.jchatmind.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/** Deliberately not a Spring Tool bean: only the isolated demo executor registers it. */
public final class DemoKnowledgeTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(DemoKnowledgeTool.class);
    private final RagService rag;
    private final DemoAccessPolicy access;

    public DemoKnowledgeTool(RagService rag, DemoAccessPolicy access) {
        this.rag = rag;
        this.access = access;
    }

    @Override public String getName() { return "KnowledgeTool"; }
    @Override public String getDescription() { return "检索公开演示知识库，返回与问题相关的资料片段。"; }
    @Override public ToolType getType() { return ToolType.FIXED; }

    @org.springframework.ai.tool.annotation.Tool(name = "KnowledgeTool",
            description = "从公开演示知识库检索资料。kbsId 必须来自当前公开知识库列表，query 为要查找的问题。返回的片段含公开资料名称；作答时应注明资料来源。")
    public String knowledgeQuery(String kbsId, String query) {
        if (!access.isPublicKb(kbsId)) return "只能检索公开演示知识库，请从当前公开知识库列表中选择。";
        if (query == null || query.isBlank() || query.length() > 4000) return "检索问题不能为空，且最多 4000 个字符。";
        try {
            List<String> fragments = rag.similaritySearch(UUID.fromString(kbsId).toString(), query.strip());
            if (fragments == null || fragments.isEmpty()) return "公开资料中未找到相关内容，请说明资料不足，不要编造。";
            return String.join("\n\n", fragments);
        } catch (RuntimeException error) {
            // Do not expose database errors, connection strings or filesystem paths to a public tool response.
            log.warn("Public knowledge search failed ({})", error.getClass().getSimpleName());
            return "公开知识库检索暂时不可用，请稍后重试；本次未获得资料。";
        }
    }
}
