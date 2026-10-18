package com.kama.jchatmind.runs;

/**
 * 排队任务的状态（与 05 的 {@link com.kama.jchatmind.execution.RunStatus} 一一对应）。
 *
 * <p>{@code QUEUED → RUNNING → {SUCCEEDED, FAILED, CANCELLED, INTERRUPTED}}；{@code QUEUED → CANCELLED}
 * 是排队期间直接取消（原子终止，不需要 worker）。终态一旦写定，迟到的 worker 不能再追加第二个终态。</p>
 */
public enum RunQueueStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
    }

    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }

    /** 数据库里的状态列与 05 的运行状态同名，转换时不做语义改写。 */
    public com.kama.jchatmind.execution.RunStatus toRunStatus() {
        return com.kama.jchatmind.execution.RunStatus.valueOf(name());
    }
}
