package com.kama.jchatmind.execution;

import java.time.Instant;

/**
 * 一条按顺序落库的运行事件。事件只在真实结束时写入（没有悬空的“开始中”事件）。
 *
 * @param eventId      服务端生成的事件标识，稳定去重键
 * @param runId        所属运行
 * @param sequence     同一运行内从 1 开始的顺序号
 * @param type         事件类型，见 {@link RunEventType}
 * @param status       结束状态，见 {@link RunEventStatus}
 * @param startedAt    事件实际开始时间（UTC）
 * @param durationMs   单调时钟测得的事件耗时，非负
 * @param toolCallId   真实工具调用 ID；模型计划但未执行时为 null
 * @param toolName     工具名（可公开的安全名称）
 * @param retrievalId  本次检索标识（工具返回的信封）
 * @param retryAttempt 模型重试的尝试序号（从 1 开始）
 * @param errorCode    安全错误分类
 * @param detail       受控的短说明（例如检索状态 OK / NO_MATCH），不是模型文本
 */
public record RunEventRecord(
        String eventId,
        String runId,
        int sequence,
        RunEventType type,
        RunEventStatus status,
        Instant startedAt,
        long durationMs,
        String toolCallId,
        String toolName,
        String retrievalId,
        Integer retryAttempt,
        String errorCode,
        String detail
) {
    public RunEventRecord {
        if (eventId == null || runId == null || type == null || status == null || startedAt == null) {
            throw new IllegalArgumentException("event id, run id, type, status and startedAt are required");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence starts at 1");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        if (retryAttempt != null && retryAttempt < 1) {
            throw new IllegalArgumentException("retry attempt starts at 1");
        }
        if (detail != null && detail.length() > 200) {
            throw new IllegalArgumentException("detail is a bounded, controlled label");
        }
    }
}