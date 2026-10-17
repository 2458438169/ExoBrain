package com.kama.jchatmind.retrieval;

/**
 * 访问失败：未授权不是一次成功检索，也不伪装成 NO_MATCH / UNAVAILABLE。
 *
 * <p>服务在调用模型与向量查询之前先执行调用方给出的授权判断；拒绝时抛出本异常，
 * 响应、缓存与日志里都不会出现无权 KB 的名称、片段、数量或距离。</p>
 */
public class RetrievalAccessDeniedException extends RuntimeException {
    public RetrievalAccessDeniedException() {
        super("knowledge base access denied");
    }
}