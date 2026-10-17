package com.kama.jchatmind.execution;

import java.time.Instant;

/**
 * 契约 §5 的运行摘要。字段顺序与契约一致，05 的附加字段排在后面（可选、增量）。
 *
 * @param schemaVersion        摘要结构版本，当前为 1
 * @param runId                服务端生成的运行标识
 * @param sessionId            所属会话
 * @param triggeringMessageId  触发本次运行的用户消息
 * @param agentId              本次实际使用的助手
 * @param agentNameAtStart     运行开始时的助手名称快照（后续改名不改变历史解释）
 * @param agentConfigHash      运行开始时助手配置的 SHA-256（只存哈希，不存配置快照）
 * @param modelCatalogId       模型目录 ID（例如 deepseek-chat）
 * @param modelNameAtStart     运行开始时服务商模型名（例如 deepseek-chat / glm-4.6）
 * @param status               当前状态，见 {@link RunStatus}
 * @param submittedAt          用户消息落库时间（UTC）
 * @param startedAt            实际开始执行时间（UTC），未开始为 null
 * @param finishedAt           终态时间（UTC），未结束为 null
 * @param durationMs           有定义的实际执行耗时（单调时钟测得），未结束为 null
 * @param queueDurationMs      提交到开始执行之间的等待耗时；无法可靠判定时为 null
 * @param inputTokens          服务商提供的输入用量，未提供为 null
 * @param outputTokens         服务商提供的输出用量，未提供为 null
 * @param totalTokens          服务商提供的总量或 input+output，未知为 null
 * @param errorCode            安全错误分类，成功为 null
 * @param answerMessageId      本次运行交付最终答复的消息 ID（第一个答复），未产出为 null
 * @param traceIncomplete      事件持久化曾失败，时间线不完整（不补造缺失事件）
 * @param heartbeatAt          最后进度时间，用于判定重启遗留的 RUNNING
 * @param origin               账号运行或游客运行
 */
public record RunSummary(
        int schemaVersion,
        String runId,
        String sessionId,
        String triggeringMessageId,
        String agentId,
        String agentNameAtStart,
        String agentConfigHash,
        String modelCatalogId,
        String modelNameAtStart,
        RunStatus status,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        Long queueDurationMs,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        String errorCode,
        String answerMessageId,
        boolean traceIncomplete,
        Instant heartbeatAt,
        RunOrigin origin
) {
    public static final int SCHEMA_VERSION = 1;

    public RunSummary {
        if (runId == null || sessionId == null || status == null || submittedAt == null || origin == null) {
            throw new IllegalArgumentException("run id, session, status, submittedAt and origin are required");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        if (queueDurationMs != null && queueDurationMs < 0) {
            throw new IllegalArgumentException("queue duration must not be negative");
        }
    }

    public RunSummary withStatus(RunStatus next, Instant finished, Long actualDurationMs,
                                 String safeErrorCode, TokenUsage usage, boolean incomplete) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("illegal run status transition: " + status + " -> " + next);
        }
        TokenUsage tokens = usage == null ? TokenUsage.UNKNOWN : usage;
        return new RunSummary(SCHEMA_VERSION, runId, sessionId, triggeringMessageId, agentId,
                agentNameAtStart, agentConfigHash, modelCatalogId, modelNameAtStart, next, submittedAt,
                startedAt, finished, actualDurationMs, queueDurationMs,
                tokens.inputTokens(), tokens.outputTokens(), tokens.totalTokens(),
                safeErrorCode, answerMessageId, traceIncomplete || incomplete,
                finished == null ? heartbeatAt : finished, origin);
    }

    public TokenUsage usage() {
        return TokenUsage.of(inputTokens, outputTokens, totalTokens);
    }
}