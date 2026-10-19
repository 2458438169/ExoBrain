package com.kama.jchatmind.runs;

import com.kama.jchatmind.execution.RunRepository;
import com.kama.jchatmind.execution.RunStatus;
import com.kama.jchatmind.execution.RunSummary;
import com.kama.jchatmind.execution.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 终态与恢复的短事务边界。
 *
 * <p>所有终态写入都在一个短事务里完成：锁任务行 → 校验 {@code fencing_token} 与 {@code status='RUNNING'}
 * → 调用 05 的 {@link RunRepository#complete}（同一个条件更新语义）→ 写任务状态。
 * 因此：</p>
 * <ul>
 *   <li>迟到的 worker（租约被接管或任务已被标记中断）一次也写不进去，不会追加第二个终态；</li>
 *   <li>模型调用不在这个事务里，事务只覆盖两次 UPDATE；</li>
 *   <li>任务行锁与 05 的终态条件共同保证“只有一个有效终态”。</li>
 * </ul>
 */
@Service
public class RunTerminalWriter {

    private static final Logger log = LoggerFactory.getLogger(RunTerminalWriter.class);
    public static final String INTERRUPTED_BY_RESTART = "INTERRUPTED_BY_RESTART";

    private final RunQueueRepository jobs;
    private final RunRepository runs;

    public RunTerminalWriter(RunQueueRepository jobs, RunRepository runs) {
        this.jobs = jobs;
        this.runs = runs;
    }

    /** 取消结果：排队任务被原子终止、运行中只记录协作式停止请求、或已经是终态。 */
    public enum CancelOutcome {
        CANCELLED_WHILE_QUEUED,
        CANCEL_REQUESTED,
        ALREADY_TERMINAL,
        NOT_FOUND
    }

    /**
     * 带 fencing 的终态写入。返回 {@code false} 表示本次写回被拒绝（任务已被接管/中断/完成），
     * 调用方不得把它当成“刚刚结束”。
     */
    @Transactional
    public boolean finish(String jobId, long fencingToken, String runId, RunStatus status, Long durationMs,
                          TokenUsage usage, String errorCode, boolean traceIncomplete) {
        if (status == null || !status.isTerminal()) {
            throw new IllegalArgumentException("finish 只接受终态");
        }
        Optional<RunQueueJob> locked = jobs.lockById(jobId);
        if (locked.isEmpty() || locked.get().fencingToken() != fencingToken
                || locked.get().status() != RunQueueStatus.RUNNING) {
            return false;
        }
        int updated = runs.complete(runId, status, Instant.now(), durationMs, usage, errorCode, traceIncomplete);
        if (updated == 0) {
            // 05 的条件更新拒绝：运行已经不是 QUEUED/RUNNING（例如已被恢复流程标记中断）。
            // 不覆盖运行状态，只把任务行同步到真实的运行状态，避免它会永远停在 RUNNING。
            String actual = jobs.runStatus(runId);
            RunQueueStatus sync = actual == null ? RunQueueStatus.INTERRUPTED : RunQueueStatus.valueOf(actual);
            jobs.markTerminal(jobId, fencingToken, sync.isTerminal() ? sync : RunQueueStatus.INTERRUPTED,
                    sync.isTerminal() ? errorCode : INTERRUPTED_BY_RESTART);
            log.warn("Run terminal write rejected by the run record; kept the existing state: run={} attempted={}",
                    runId, status);
            return false;
        }
        jobs.markTerminal(jobId, fencingToken, RunQueueStatus.valueOf(status.name()), errorCode);
        return true;
    }

    /** 取消：排队中直接原子终止；运行中只记录协作式停止请求。 */
    @Transactional
    public CancelOutcome cancel(String runId) {
        Optional<RunQueueJob> found = jobs.findByRunId(runId);
        if (found.isEmpty()) {
            return CancelOutcome.NOT_FOUND;
        }
        Optional<RunQueueJob> locked = jobs.lockById(found.get().id());
        if (locked.isEmpty()) {
            return CancelOutcome.NOT_FOUND;
        }
        RunQueueJob job = locked.get();
        if (job.status() == RunQueueStatus.QUEUED) {
            Optional<RunQueueJob> cancelled = jobs.cancelQueued(job.id());
            if (cancelled.isEmpty()) {
                return CancelOutcome.ALREADY_TERMINAL;
            }
            runs.complete(runId, RunStatus.CANCELLED, Instant.now(), null, TokenUsage.UNKNOWN,
                    "CANCELLED_BEFORE_START", false);
            return CancelOutcome.CANCELLED_WHILE_QUEUED;
        }
        if (job.status() == RunQueueStatus.RUNNING) {
            return jobs.requestCancel(job.id()) ? CancelOutcome.CANCEL_REQUESTED : CancelOutcome.CANCEL_REQUESTED;
        }
        return CancelOutcome.ALREADY_TERMINAL;
    }

    /**
     * 崩溃恢复：只处理租约已经过期的 {@code RUNNING}。
     *
     * <p>多实例启动时，其他实例正在续租的任务租约不会过期，因此不会被标记为中断。已经执行过
     * （可能已经产生外部副作用）的运行不会自动重放，而是持久化为 {@code INTERRUPTED}，
     * 保留已入库的消息与事件；用户重新发起时形成新的运行。</p>
     */
    @Transactional
    public int interruptExpired(int limit) {
        int interrupted = 0;
        for (RunQueueJob candidate : jobs.expiredRunning(limit)) {
            Optional<RunQueueJob> locked = jobs.lockById(candidate.id());
            if (locked.isEmpty()) {
                continue;
            }
            RunQueueJob job = locked.get();
            // 加锁后重新判断：期间被续租或被正常完成的任务不能标成中断。
            if (job.status() != RunQueueStatus.RUNNING || job.leaseExpiresAt() == null
                    || !job.leaseExpiresAt().isBefore(Instant.now())) {
                continue;
            }
            // 用户已经请求取消的任务不能记成“重启中断”：续租会刻意忽略 cancel_requested=true，
            // 所以它的租约必然过期，但真正的原因是取消，终态必须是 CANCELLED。
            boolean cancelRequested = job.cancelRequested();
            RunStatus terminal = cancelRequested ? RunStatus.CANCELLED : RunStatus.INTERRUPTED;
            String errorCode = cancelRequested ? "CANCELLED_BY_USER" : INTERRUPTED_BY_RESTART;
            RunSummary run = runs.findById(job.runId());
            Long durationMs = run == null || run.startedAt() == null ? null
                    : Math.max(0L, Duration.between(run.startedAt(), Instant.now()).toMillis());
            int updated = runs.complete(job.runId(), terminal, Instant.now(), durationMs,
                    TokenUsage.UNKNOWN, errorCode, false);
            if (updated == 0) {
                // 运行在此期间已经进入别的终态；把任务行同步过去，不覆盖它。
                String actual = jobs.runStatus(job.runId());
                RunQueueStatus sync = actual == null ? RunQueueStatus.INTERRUPTED : RunQueueStatus.valueOf(actual);
                jobs.markTerminal(job.id(), job.fencingToken(),
                        sync.isTerminal() ? sync : RunQueueStatus.INTERRUPTED, errorCode);
                continue;
            }
            jobs.markTerminal(job.id(), job.fencingToken(), RunQueueStatus.valueOf(terminal.name()), errorCode);
            interrupted++;
            if (cancelRequested) {
                log.info("Run was cancelled by the user before its lease expired; terminal state recorded as "
                        + "CANCELLED: run={}", job.runId());
            } else {
                log.warn("Run lease expired; the run was marked INTERRUPTED and will not be replayed: run={} "
                        + "attempts={}", job.runId(), job.attempts());
            }
        }
        return interrupted;
    }
}
