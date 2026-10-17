package com.kama.jchatmind.retrieval;

import java.util.List;
import java.util.function.Predicate;

/** 结构化检索入口：返回共享契约 §2 的 {@link RetrievalEnvelope}。 */
public interface RetrievalService {

    /**
     * 在单个获准知识库内检索。
     *
     * @param maySearchKb 调用方的授权判断，<b>在解析模型绑定、调用向量服务与查询向量之前</b>执行；
     *                    返回 false 时抛 {@link RetrievalAccessDeniedException}（访问失败，不是 NO_MATCH）。
     */
    RetrievalEnvelope retrieve(String kbId, String query, Predicate<String> maySearchKb);

    /**
     * 过渡适配：给尚未改造的调用方（废弃的 {@code RagService.similaritySearch}）使用。
     * 不可用时抛出异常而不是返回空列表——旧行为就是抛异常，不能把服务失败伪装成“没有资料”。
     * 返回的命中已经带来源位置；新代码请直接用 {@link #retrieve}。
     */
    List<RetrievalHit> requireHits(String kbId, String query, Predicate<String> maySearchKb);
}