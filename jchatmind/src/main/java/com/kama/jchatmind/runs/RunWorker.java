package com.kama.jchatmind.runs;

import com.kama.jchatmind.execution.RunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 对话任务 worker：定时用<b>一条语句</b>领取 {@code QUEUED}，再交给有界执行器运行。
 *
 * <p>可靠来源是数据库队列：{@link #wake()} 只是提交事务后的一次唤醒，进程重启后
 * {@code @Scheduled} 轮询仍会继续领取。多实例安全：{@code FOR UPDATE SKIP LOCKED} 保证同一任务
 * 只会被一个实例领取；租约过期的 {@code RUNNING} <b>不会</b>被重新领取，而是由
 * {@link RunTerminalWriter#interruptExpired()} 标记为 {@code INTERRUPTED}——所以启动时不会把其他实例
 * 正在执行的工作标成中断。</p>
 */
@Component
@Slf4j
public class RunWorker {

    private final RunQueueRepository jobs;
    private final RunExecutor executor;
    private final RunTerminalWriter terminalWriter;
    private final RunRepository runs;
    private final RunQueueSettings settings;
    private final ThreadPoolTaskExecutor pool;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ReentrantLock pollLock = new ReentrantLock();
    private final String workerId;

    public RunWorker(RunQueueRepository jobs, RunExecutor executor, RunTerminalWriter terminalWriter,
                     RunRepository runs, RunQueueSettings settings) {
        this.jobs = jobs;
        this.executor = executor;
        this.terminalWriter = terminalWriter;
        this.runs = runs;
        this.settings = settings;
        this.workerId = newWorkerId();
        this.pool = new ThreadPoolTaskExecutor();
        this.pool.setCorePoolSize(settings.maxConcurrentRuns());
        this.pool.setMaxPoolSize(settings.maxConcurrentRuns());
        this.pool.setQueueCapacity(0);
        this.pool.setThreadNamePrefix("run-worker-");
        this.pool.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        this.pool.initialize();
    }

    @Scheduled(fixedDelayString = "${app.runs.poll-interval-ms:2000}", initialDelayString = "4000")
    public void poll() {
        if (!settings.enabled()) {
            return;
        }
        if (!pollLock.tryLock()) {
            return;
        }
        try {
            try {
                terminalWriter.interruptExpired(settings.maxConcurrentRuns() * 4);
            } catch (RuntimeException failure) {
                log.warn("识别租约过期的对话运行失败: {}", failure.getClass().getSimpleName());
            }
            int capacity = settings.maxConcurrentRuns() - inFlight.get();
            for (int index = 0; index < capacity; index++) {
                Optional<RunQueueJob> claimed;
                try {
                    claimed = jobs.claim(workerId, settings.leaseSeconds());
                } catch (RuntimeException failure) {
                    log.warn("领取对话任务失败: {}", failure.getClass().getSimpleName());
                    return;
                }
                if (claimed.isEmpty()) {
                    return;
                }
                submit(claimed.get());
            }
        } finally {
            pollLock.unlock();
        }
    }

    /** 提交事务后的唤醒：立即尝试领取一次（不阻塞提交线程，也不是可靠来源）。 */
    public void wake() {
        if (!settings.enabled()) {
            return;
        }
        try {
            poll();
        } catch (RuntimeException failure) {
            log.warn("对话任务唤醒失败（轮询会继续尝试）: {}", failure.getClass().getSimpleName());
        }
    }

    /** 同步领取并执行至多 {@code max} 条（测试与运维一次性排空用）。返回实际执行条数。 */
    public int drain(int max) {
        int executed = 0;
        for (int index = 0; index < Math.max(0, max); index++) {
            Optional<RunQueueJob> claimed = jobs.claim(workerId, settings.leaseSeconds());
            if (claimed.isEmpty()) {
                return executed;
            }
            runNow(claimed.get());
            executed++;
        }
        return executed;
    }

    /** 单步领取+执行，便于断言“提交后重启仍能领取”。 */
    public boolean runOne() {
        Optional<RunQueueJob> claimed = jobs.claim(workerId, settings.leaseSeconds());
        if (claimed.isEmpty()) {
            return false;
        }
        runNow(claimed.get());
        return true;
    }

    /** 只领取不执行：测试租约/fencing 时使用。 */
    public Optional<RunQueueJob> claimOnly() {
        return jobs.claim(workerId, settings.leaseSeconds());
    }

    public int interruptExpired() {
        return terminalWriter.interruptExpired(settings.maxConcurrentRuns() * 4);
    }

    public String workerId() {
        return workerId;
    }

    private void submit(RunQueueJob job) {
        inFlight.incrementAndGet();
        try {
            pool.execute(() -> {
                try {
                    runNow(job);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        } catch (RuntimeException rejected) {
            inFlight.decrementAndGet();
            log.warn("对话任务执行被拒绝（线程池已满）: jobId={}", job.id());
        }
    }

    private void runNow(RunQueueJob job) {
        RunLease lease = new RunLease(job, jobs, terminalWriter, runs, settings.leaseSeconds());
        try {
            executor.execute(job, lease);
        } catch (RuntimeException failure) {
            log.warn("对话任务执行异常: jobId={} cause={}", job.id(), failure.getClass().getSimpleName());
        }
    }

    private static String newWorkerId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception unknown) {
            host = "unknown";
        }
        String jvm = ManagementFactory.getRuntimeMXBean().getName();
        return host + "/" + jvm + "/" + UUID.randomUUID().toString().substring(0, 8);
    }
}
