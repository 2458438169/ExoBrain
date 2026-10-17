package com.kama.jchatmind.execution;

import java.time.Instant;
import java.util.List;

/**
 * 运行与事件的持久化接口（任务 07 的扩展点）。
 *
 * <p>任务 07 复用：{@link #create} 建运行、{@link #appendEvent} 追加事件、{@link #complete}
 * 幂等终态更新（终态不会被迟到的 RUNNING 覆盖）、{@link #touch} 心跳。加入队/领取/租约时应新增
 * 独立字段与语句，不要改写这些语义。</p>
 */
public interface RunRepository {

    void create(RunSummary summary);

    RunSummary findById(String runId);

    List<RunSummary> findBySessionId(String sessionId, RunOrigin origin);

    void appendEvent(RunEventRecord event);

    List<RunEventRecord> findEvents(String runId);

    /**
     * 幂等的终态更新：只有 QUEUED/RUNNING 或同一个目标状态才会被写入。
     *
     * @return 实际更新的行数；0 表示“终态已确定（或运行不存在）”，调用方不得把运行当成刚结束
     */
    int complete(String runId, RunStatus target, Instant finishedAt, Long durationMs,
                 TokenUsage usage, String errorCode, boolean traceIncomplete);

    int setAnswerMessageId(String runId, String answerMessageId);

    int touch(String runId);
}