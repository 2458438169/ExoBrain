package com.kama.jchatmind.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.retrieval.RetrievalAuthorization;
import com.kama.jchatmind.retrieval.RetrievalEnvelope;
import com.kama.jchatmind.retrieval.RetrievalErrorCode;
import com.kama.jchatmind.retrieval.RetrievalService;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeTools implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RetrievalService retrieval;

    public KnowledgeTools(RetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    @Override
    public String getName() {
        return "KnowledgeTool";
    }

    @Override
    public String getDescription() {
        return "用于从知识库执行语义检索（RAG）。输入知识库 ID 和查询文本，返回与查询最相关的正文片段及其来源位置。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    /**
     * 返回结构化检索结果（{@code RetrievalEnvelope} 的 JSON）：包含来源位置、索引版本与 L2 距离，
     * 状态区分 OK / NO_MATCH / UNAVAILABLE。工具名与参数名保持 {@code KnowledgeTool(kbsId, query)} 不变。
     *
     * <p>授权边界：运行时的 {@code ScopedToolCallback} 与 runtimeGuard 已经把模型给出的 kbsId 限定在当前
     * 助手允许的知识库集合内；这里把“只检索本次调用参数里的那个 KB”作为显式授权判断交给检索服务，
     * 服务在执行模型/向量查询之前先执行它，未授权时抛访问失败而不是返回结果。</p>
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "KnowledgeTool",
            description = "从指定知识库中执行相似性检索（RAG）。参数为知识库 ID（kbsId）和查询文本（query），"
                    + "返回结构化 JSON：status 为 OK 时 hits 内含片段正文与来源位置（documentId/行号/标题路径），"
                    + "status 为 NO_MATCH 表示资料不足，UNAVAILABLE 表示检索不可用。"
    )
    public String knowledgeQuery(String kbsId, String query) {
        RetrievalEnvelope envelope = retrieval.retrieve(kbsId, query, RetrievalAuthorization.argumentBound(kbsId));
        return serialize(envelope);
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