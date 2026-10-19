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

    /**
     * 只有运行仍是 RUNNING 时才挂上答案关联。返回 0 表示运行已进入终态（被中断/失败/取消）或关联已存在，
     * 调用方必须回滚这次答案写入，不能留下“运行已中断 + 又有最终答案”的组合。
     */
    int setAnswerMessageIdIfRunning(String runId, String answerMessageId);

    int touch(String runId);

    /**
     * 有界心跳更新：被行锁挡住时返回 0，而不是一直等下去。
     *
     * <p>{@link #touch} 会无超时地等行锁。检查点最后一步用它，等于把刚确认过的租约有效性重新交给一次可能
     * 无限阻塞的写：摘要行一被锁，租约就可能在这条 {@code UPDATE} 里过期，返回后模型重试照样发出去（R4-01）。</p>
     *
     * @return 实际更新的行数；0 表示被行锁挡住、运行不存在或数据库不可用
     */
    int touchWithin(String runId, int lockTimeoutMs);
}