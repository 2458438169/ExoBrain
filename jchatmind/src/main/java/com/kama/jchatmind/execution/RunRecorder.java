package com.kama.jchatmind.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次运行的事件记录器：只记录真实发生、已经结束的动作，耗时用同一进程内的单调时钟测量。
 *
 * <p>事件持久化失败不会中断执行：保留已有记录、置 {@code traceIncomplete} 并记一条安全日志，
 * 不伪造缺失的时间线。事件 ID 与顺序号在失败时也会消耗，避免重复使用同一个键。</p>
 */
public final class RunRecorder {

    private static final Logger log = LoggerFactory.getLogger(RunRecorder.class);

    private final String runId;
    private final RunEventSink sink;
    private final long startNanos;
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicBoolean traceIncomplete = new AtomicBoolean();
    private final AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.UNKNOWN);

    public RunRecorder(String runId, RunEventSink sink, long startNanos) {
        this.runId = runId;
        this.sink = sink == null ? RunEventSink.NONE : sink;
        this.startNanos = startNanos;
    }

    /** 没有运行记录可写时使用（例如单元测试或未启用追踪的调用方）。 */
    public static RunRecorder noop(String runId) {
        return new RunRecorder(runId, RunEventSink.NONE, System.nanoTime());
    }

    /** 单调时钟起点，供调用方在真实调用前后取时间。 */
    public static long now() {
        return System.nanoTime();
    }

    public String runId() {
        return runId;
    }

    public boolean traceIncomplete() {
        return traceIncomplete.get();
    }

    public TokenUsage usage() {
        return usage.get();
    }

    public long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    /** 整次运行的实际执行耗时（单调时钟）。 */
    public long durationMillis() {
        return elapsedMillis(startNanos);
    }

    public void modelCall(int attempt, Instant startedAt, long durationMs,
                          RunEventStatus status, String errorCode) {
        modelCall(attempt, startedAt, durationMs, status, errorCode, null);
    }

    /**
     * 模型调用事件；{@code detail} 只放受控短标签（例如模型调用的序号、是否在检索之后、
     * 供应商失败的白名单诊断），总长受 {@code detail} 列约束。
     */
    public void modelCall(int attempt, Instant startedAt, long durationMs,
                          RunEventStatus status, String errorCode, String detail) {
        record(attempt > 1 ? RunEventType.MODEL_RETRY : RunEventType.MODEL_CALL, startedAt, durationMs, status,
                attempt, null, null, null, errorCode, detail);
    }

    public void toolCall(String toolCallId, String toolName, Instant startedAt, long durationMs,
                         RunEventStatus status, String errorCode) {
        record(RunEventType.TOOL_CALL, startedAt, durationMs, status, null, toolCallId, toolName, null,
                errorCode, null);
    }

    public void retrieval(String toolCallId, String retrievalId, String retrievalStatus, Instant startedAt,
                          long durationMs, RunEventStatus status, String errorCode) {
        record(RunEventType.RETRIEVAL, startedAt, durationMs, status, null, toolCallId, null, retrievalId,
                errorCode, retrievalStatus);
    }

    public void persistedMessage(Instant startedAt, long durationMs, RunEventStatus status, String errorCode) {
        record(RunEventType.PERSIST_MESSAGE, startedAt, durationMs, status, null, null, null, null,
                errorCode, null);
    }

    /** 零耗时的事件（步数上限、运行失败原因）；detail 只放受控短标签。 */
    public void note(RunEventType type, RunEventStatus status, String errorCode, String detail) {
        record(type, Instant.now(), 0L, status, null, null, null, null, errorCode, detail);
    }

    /** 累加服务商提供的用量；未知用量保持 null。 */
    public void addUsage(TokenUsage reported) {
        if (reported == null || !reported.known()) {
            return;
        }
        usage.updateAndGet(current -> current.plus(reported));
    }

    public void answerMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        try {
            sink.setAnswerMessageId(runId, messageId);
        } catch (RuntimeException failure) {
            markIncomplete("answer link", failure);
        }
    }

    private void record(RunEventType type, Instant startedAt, long durationMs, RunEventStatus status,
                        Integer retryAttempt, String toolCallId, String toolName, String retrievalId,
                        String errorCode, String detail) {
        RunEventRecord event = new RunEventRecord(UUID.randomUUID().toString(), runId, sequence.incrementAndGet(),
                type, status, startedAt, Math.max(0L, durationMs), toolCallId, toolName, retrievalId,
                retryAttempt, errorCode, detail);
        try {
            sink.append(event);
            sink.touch(runId);
        } catch (RuntimeException failure) {
            markIncomplete(type.name(), failure);
        }
    }

    private void markIncomplete(String what, RuntimeException failure) {
        traceIncomplete.set(true);
        log.warn("Run trace persistence failed: run={} part={} cause={}", runId, what,
                failure.getClass().getSimpleName());
    }
}