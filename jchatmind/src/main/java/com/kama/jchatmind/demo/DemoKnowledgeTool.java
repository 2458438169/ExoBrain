package com.kama.jchatmind.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import com.kama.jchatmind.retrieval.RetrievalAccessDeniedException;
import com.kama.jchatmind.retrieval.RetrievalEnvelope;
import com.kama.jchatmind.retrieval.RetrievalErrorCode;
import com.kama.jchatmind.retrieval.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 公开演示检索工具：只读，且只允许检索发布白名单里的知识库（游客只读）。
 *
 * <p>输出与账号工具一致，都是 {@code RetrievalEnvelope} 的 JSON，不会出现“一个工具返回 JSON、
 * 另一个返回没有来源的字符串”。白名单校验在调用检索服务之前完成，服务内部还会再执行一次同样的授权判断。</p>
 */
public final class DemoKnowledgeTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(DemoKnowledgeTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_QUERY_CHARACTERS = 4000;

    private final RetrievalService retrieval;
    private final DemoAccessPolicy access;

    public DemoKnowledgeTool(RetrievalService retrieval, DemoAccessPolicy access) {
        this.retrieval = retrieval;
        this.access = access;
    }

    @Override public String getName() { return "KnowledgeTool"; }
    @Override public String getDescription() { return "检索公开演示知识库，返回与问题相关的资料片段及其来源位置。"; }
    @Override public ToolType getType() { return ToolType.FIXED; }

    @org.springframework.ai.tool.annotation.Tool(name = "KnowledgeTool",
            description = "从公开演示知识库检索资料。kbsId 必须来自当前公开知识库列表，query 为要查找的问题。"
                    + "返回结构化 JSON：status 为 OK 时 hits 内含片段正文与来源位置；NO_MATCH 表示资料不足；"
                    + "UNAVAILABLE 表示检索不可用。作答时应注明资料来源。")
    public String knowledgeQuery(String kbsId, String query) {
        if (!access.isPublicKb(kbsId)) {
            return unavailable(RetrievalErrorCode.KB_NOT_ALLOWED);
        }
        if (query == null || query.isBlank() || query.length() > MAX_QUERY_CHARACTERS) {
            return unavailable(RetrievalErrorCode.INVALID_REQUEST);
        }
        try {
            return serialize(retrieval.retrieve(UUID.fromString(kbsId).toString(), query.strip(),
                    access::isPublicKb));
        } catch (RetrievalAccessDeniedException denied) {
            return unavailable(RetrievalErrorCode.KB_NOT_ALLOWED);
        } catch (RuntimeException error) {
            // 不向公开响应暴露数据库错误、连接串或文件路径。
            log.warn("Public knowledge search failed ({})", error.getClass().getSimpleName());
            return unavailable(RetrievalErrorCode.RETRIEVAL_UNAVAILABLE);
        }
    }

    /** 检索之前就失败的调用没有命中任何资料：返回空 hits 的 UNAVAILABLE，而不是编造结果。 */
    private static String unavailable(String errorCode) {
        return serialize(RetrievalEnvelope.unavailable(UUID.randomUUID().toString(), errorCode));
    }

    private static String serialize(RetrievalEnvelope envelope) {
        try {
            return JSON.writeValueAsString(envelope);
        } catch (JsonProcessingException impossible) {
            return "{\"schemaVersion\":1,\"retrievalId\":\"" + envelope.retrievalId()
                    + "\",\"status\":\"UNAVAILABLE\",\"hits\":[],\"errorCode\":\""
                    + RetrievalErrorCode.RETRIEVAL_UNAVAILABLE + "\"}";
        }
    }
}