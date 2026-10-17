package com.kama.jchatmind.service;

import java.util.List;

public interface RagService {
    float[] embed(String text);

    /** Index and query a knowledge base with the same bound embedding model. */
    float[] embed(String kbId, String text);

    /**
     * 过渡适配（仅保留给尚未改造的调用方与既有测试）：只返回片段正文，没有来源位置、没有状态。
     *
     * <p>不可用时抛出异常，不会返回空列表冒充“没有资料”。所有工具输出已经统一改为
     * {@code RetrievalEnvelope}（见 {@code KnowledgeTools} / {@code DemoKnowledgeTool}），
     * 新代码请直接使用 {@code RetrievalService}。</p>
     *
     * @deprecated 用 {@code RetrievalService#retrieve} 取带来源位置的结构化结果。
     */
    @Deprecated
    List<String> similaritySearch(String kbId, String title);
}