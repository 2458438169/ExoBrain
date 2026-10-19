package com.kama.jchatmind.runs;

import com.kama.jchatmind.execution.RunRecorder;
import com.kama.jchatmind.execution.RunRepository;
import com.kama.jchatmind.execution.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private volatile ScheduledFuture<?> heartbeat;
    private volatile ScheduledExecutorService heartbeatThread;
    private volatile boolean leaseLost;

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

    public long fencingToken() {
        return job.fencingToken();
    }

    /**
     * 启动租约心跳：与步骤检查点无关地续租。
     *
     * <p>模型调用可能远长于租约（默认 120 秒，验收时压到 10 秒），只在步骤之间续租会让仍活着的运行
     * 被过期扫描标记成 {@code INTERRUPTED}。心跳只做一次短 UPDATE，不进入模型/工具调用，也不占用
     * 调用方事务。</p>
     *
     * <p>每个租约使用<b>自己的</b>单线程调度器：共用一个线程时，一个任务的行锁等待会连带堵死其它运行的心跳。
     * 续租本身也带 {@code lock_timeout}，被挡住时只跳过这一拍。</p>
     */
    public void startHeartbeat() {
        if (heartbeat != null) {
            return;
        }
        ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread worker = new Thread(runnable, "run-lease-heartbeat-" + job.runId());
            worker.setDaemon(true);
            return worker;
        });
        long intervalMs = Math.max(300L, leaseSeconds * 1000L / 3);
        heartbeatThread = thread;
        heartbeat = thread.scheduleWithFixedDelay(this::beat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** 停止心跳：终态写入前与执行结束时都要调用。 */
    public void close() {
        ScheduledFuture<?> running = heartbeat;
        heartbeat = null;
        if (running != null) {
            running.cancel(false);
        }
        ScheduledExecutorService thread = heartbeatThread;
        heartbeatThread = null;
        if (thread != null) {
            thread.shutdownNow();
        }
    }

    /** 心跳续租的 lock_timeout：取租约的 1/3，最多 2 秒，避免把心跳拖成一整拍。 */
    private int heartbeatLockTimeoutMs() {
        return (int) Math.max(200L, Math.min(2_000L, leaseSeconds * 1000L / 3));
    }

    private void beat() {
        try {
            RunQueueRepository.RenewOutcome outcome = jobs.renewHeartbeat(
                    job.id(), job.fencingToken(), leaseSeconds, heartbeatLockTimeoutMs());
            if (outcome == RunQueueRepository.RenewOutcome.RENEWED) {
                runs.touch(job.runId());
                return;
            }
            if (outcome == RunQueueRepository.RenewOutcome.BLOCKED) {
                // 这一拍被其它事务的行锁挡住：下一拍重试，绝不据此把运行判成租约丢失。
                return;
            }
            // LOST 有两种完全不同的原因：用户取消（renew 故意不接受 cancel_requested=true）与租约被接管/终态。
            RunQueueJob current = jobs.findById(job.id()).orElse(null);
            if (current != null && current.cancelRequested() && current.status() == RunQueueStatus.RUNNING) {
                cancelled = true;
                return;
            }
            leaseLost = true;
        } catch (RuntimeException failure) {
            log.warn("Run lease heartbeat failed: run={} cause={}", job.runId(), failure.getClass().getSimpleName());
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 检查点：续租 + 取消检查 + 刷新 05 的心跳。失败时必须立刻停止，不再发起新的调用。
     *
     * <p>取消与租约丢失分开处理：续租被拒时先看是否只是收到了取消请求——那种情况必须走
     * {@link RunCancelledException}（最终记为 {@code CANCELLED}），不能被当成崩溃中断。</p>
     */
    public void checkpoint() {
        if (cancelled) {
            throw new RunCancelledException("本次运行已收到取消请求");
        }
        if (leaseLost) {
            throw new RunLeaseLostException("租约已丢失，本次执行不会写回结果");
        }
        RunQueueRepository.RenewOutcome outcome = jobs.renewHeartbeat(
                job.id(), job.fencingToken(), leaseSeconds, heartbeatLockTimeoutMs());
        if (outcome == RunQueueRepository.RenewOutcome.BLOCKED) {
            // 行锁短暂冲突：心跳线程会继续续租，这里不发起失败，也不改写任何状态。
            log.debug("Run lease renewal was blocked by a row lock; heartbeat keeps trying: run={}", job.runId());
            return;
        }
        if (outcome == RunQueueRepository.RenewOutcome.LOST) {
            RunQueueJob current = jobs.findById(job.id()).orElse(null);
            if (current != null && current.cancelRequested() && current.status() == RunQueueStatus.RUNNING) {
                cancelled = true;
                throw new RunCancelledException("本次运行已收到取消请求");
            }
            leaseLost = true;
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
        // 先停心跳：终态事务之后不再续租，避免把已经结束的任务重新拉回 RUNNING 语义。
        close();
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
