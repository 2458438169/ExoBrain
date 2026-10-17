package com.kama.jchatmind.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 导入 worker：定时用短语句领取到期任务，再交给<b>有界</b>的本地执行器运行。
 *
 * <p>多实例安全：领取是一条 {@code FOR UPDATE SKIP LOCKED} 语句，同一任务只会有一个有效租约；
 * 进程重启后，租约过期的 {@code RUNNING} 任务会被重新领取（{@link IngestionJobRepository#claim}）。
 * 后台线程不读取请求身份：任务里的 {@code actor_user_id} 与资源 id 都来自提交事务。</p>
 */
@Component
@Slf4j
public class IngestionWorker {

    private final IngestionJobRepository jobs;
    private final IngestionJobRunner runner;
    private final IngestionSettings settings;
    private final ThreadPoolTaskExecutor executor;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final String workerId;

    public IngestionWorker(IngestionJobRepository jobs, IngestionJobRunner runner, IngestionSettings settings) {
        this.jobs = jobs;
        this.runner = runner;
        this.settings = settings;
        this.workerId = newWorkerId();
        this.executor = new ThreadPoolTaskExecutor();
        this.executor.setCorePoolSize(settings.maxConcurrentJobs());
        this.executor.setMaxPoolSize(settings.maxConcurrentJobs());
        this.executor.setQueueCapacity(0);
        this.executor.setThreadNamePrefix("ingestion-worker-");
        this.executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        this.executor.initialize();
    }

    @Scheduled(fixedDelayString = "${app.ingestion.poll-interval-ms:5000}", initialDelayString = "2000")
    public void poll() {
        if (!settings.enabled()) {
            return;
        }
        try {
            jobs.failExhausted();
        } catch (RuntimeException failure) {
            log.warn("收尾用完尝试次数的导入任务失败: {}", failure.getClass().getSimpleName());
        }
        int capacity = settings.maxConcurrentJobs() - inFlight.get();
        for (int index = 0; index < capacity; index++) {
            Optional<IngestionJob> claimed;
            try {
                claimed = jobs.claim(workerId, settings.leaseSeconds());
            } catch (RuntimeException failure) {
                log.warn("领取导入任务失败: {}", failure.getClass().getSimpleName());
                return;
            }
            if (claimed.isEmpty()) {
                return;
            }
            inFlight.incrementAndGet();
            try {
                executor.execute(() -> {
                    try {
                        runner.run(claimed.get());
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            } catch (RuntimeException rejected) {
                inFlight.decrementAndGet();
                log.warn("导入任务执行被拒绝（线程池已满）: jobId={}", claimed.get().id());
                return;
            }
        }
    }

    /**
     * 同步领取并执行至多 {@code max} 条任务（测试与运维一次性排空用）。返回实际执行条数。
     */
    public int drain(int max) {
        int executed = 0;
        for (int index = 0; index < Math.max(0, max); index++) {
            Optional<IngestionJob> claimed = jobs.claim(workerId, settings.leaseSeconds());
            if (claimed.isEmpty()) {
                return executed;
            }
            runner.run(claimed.get());
            executed++;
        }
        return executed;
    }

    /** 单步领取+执行，便于断言“重启后能重新领取超时任务”。 */
    public boolean runOne() {
        Optional<IngestionJob> claimed = jobs.claim(workerId, settings.leaseSeconds());
        if (claimed.isEmpty()) {
            return false;
        }
        runner.run(claimed.get());
        return true;
    }

    public String workerId() {
        return workerId;
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
