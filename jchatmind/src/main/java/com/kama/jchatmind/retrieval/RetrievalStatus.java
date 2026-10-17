package com.kama.jchatmind.retrieval;

/**
 * 检索状态（契约 §2）。
 *
 * <ul>
 *   <li>{@link #OK}：至少一个 hit。</li>
 *   <li>{@link #NO_MATCH}：检索本身成功，但没有足够相关的资料（空索引或超过校准阈值），hits 必须为空。</li>
 *   <li>{@link #UNAVAILABLE}：服务、模型或索引不可用，hits 必须为空；不能拿异常当“资料不足”。</li>
 * </ul>
 */
public enum RetrievalStatus {
    OK, NO_MATCH, UNAVAILABLE
}