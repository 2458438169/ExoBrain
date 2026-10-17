package com.kama.jchatmind.execution;

/**
 * 运行事件类型：只记录真实发生的、可核验的动作（共享契约 §5）。
 *
 * <ul>
 *   <li>{@code MODEL_CALL}：一次真实的模型请求（首次尝试）。</li>
 *   <li>{@code MODEL_RETRY}：上游繁忙后的同一次请求的重试（{@code retryAttempt} 从 2 开始）。</li>
 *   <li>{@code TOOL_CALL}：一次真实执行完成的工具调用（模型“计划”但未执行的调用不产生事件）。</li>
 *   <li>{@code RETRIEVAL}：工具返回的检索信封，按 {@code retrievalId} 关联。</li>
 *   <li>{@code PERSIST_MESSAGE}：一次消息落库。</li>
 *   <li>{@code STEP_LIMIT}：达到循环步数上限而结束（不是失败，但需要明确记录）。</li>
 *   <li>{@code ERROR}：本次运行以失败终止（安全错误码）。</li>
 * </ul>
 *
 * <p>不记录模型私有推理文本、提示词全文、API Key、令牌/哈希、内网地址或其他用户文档。</p>
 */
public enum RunEventType {
    MODEL_CALL,
    MODEL_RETRY,
    TOOL_CALL,
    RETRIEVAL,
    PERSIST_MESSAGE,
    STEP_LIMIT,
    ERROR
}
