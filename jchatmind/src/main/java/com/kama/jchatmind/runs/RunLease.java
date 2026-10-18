package com.kama.jchatmind.runs;

import com.kama.jchatmind.execution.RunRecorder;
import com.kama.jchatmind.execution.RunRepository;
import com.kama.jchatmind.execution.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次已领取运行的租约句柄：每个检查点续租并检查取消请求，终态写入带 fencing。
 *
 * <p>执行器只通过这个对象与数据库交互，因此模型/工具调用永远不会占用数据库事务。
 * {@link #checkpoint()} 在以下情况抛出：</p>
 * <ul>
 *   <li>{@link RunCancelledException}：收到取消请求（协作式停止，不撤回已发出的外部请求）；</li>
 *   <li>{@link RunLeaseLostException}：租约已过期或任务已被恢复流程标记中断——本次执行不得再写任何结果。</li>
 * </ul>
 */
public class RunLease {

    private static final Logger log = LoggerFactory.getLogger(RunLease.class);

    private final RunQueueJob job;
    private final RunQueueRepository jobs;
    private final RunTerminalWriter writer;
    private final RunRepository runs;
    private final int leaseSeconds;
    private volatile boolean cancelled;

    public RunLease(RunQueueJob job, RunQueueRepository jobs, RunTerminalWriter writer, RunRepository runs,
                    int leaseSeconds) {
        this.job = job;
        this.jobs = jobs;
        this.writer = writer;
        this.runs = runs;
        this.leaseSeconds = Math.max(1, leaseSeconds);
    }

    public RunQueueJob job() {
        return job;
    }

    public String jobId() {
        return job.id();
    }

    public String runId() {
        return job.runId();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** 检查点：续租 + 取消检查 + 刷新 05 的心跳。失败时必须立刻停止，不再发起新的调用。 */
    public void checkpoint() {
        if (cancelled) {
            throw new RunCancelledException("本次运行已收到取消请求");
        }
        boolean renewed = jobs.renew(job.id(), job.fencingToken(), leaseSeconds);
        if (!renewed) {
            RunQueueJob current = jobs.findById(job.id()).orElse(null);
            if (current != null && current.cancelRequested() && current.status() == RunQueueStatus.RUNNING) {
                cancelled = true;
                throw new RunCancelledException("本次运行已收到取消请求");
            }
            log.warn("Run lease lost; the worker stops without writing a result: run={}", job.runId());
            throw new RunLeaseLostException("租约已丢失，本次执行不会写回结果");
        }
        try {
            // 05 的读取层用心跳判断“重启遗留的 RUNNING”；续租时同步刷新，避免活跃运行被投影成中断。
            runs.touch(job.runId());
        } catch (RuntimeException failure) {
            log.warn("Run heartbeat could not be refreshed: run={}", job.runId());
        }
    }

    public boolean succeeded(RunRecorder recorder) {
        return finish(RunStatus.SUCCEEDED, recorder, null);
    }

    public boolean failed(RunRecorder recorder, String errorCode) {
        return finish(RunStatus.FAILED, recorder, errorCode);
    }

    public boolean cancelledRun(RunRecorder recorder) {
        cancelled = true;
        return finish(RunStatus.CANCELLED, recorder, "CANCELLED_BY_USER");
    }

    private boolean finish(RunStatus status, RunRecorder recorder, String errorCode) {
        try {
            boolean applied = writer.finish(job.id(), job.fencingToken(), job.runId(), status,
                    recorder == null ? null : recorder.durationMillis(),
                    recorder == null ? null : recorder.usage(), errorCode,
                    recorder != null && recorder.traceIncomplete());
            if (!applied) {
                log.warn("Run terminal state was rejected (lease taken over or already final): run={} status={}",
                        job.runId(), status);
            }
            return applied;
        } catch (RuntimeException failure) {
            log.error("Run terminal state could not be persisted: run={} status={}", job.runId(), status,
                    failure);
            return false;
        }
    }
}
