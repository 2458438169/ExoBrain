package com.kama.jchatmind.runs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 队列与 worker 的边界配置：{@code app.runs.*}（默认值见 docs/development/deliveries/07.md）。
 *
 * <p>数据库队列不能变成无限输入缓存：排队深度、每个身份的活动运行数、并发执行数、租约时长都有上限。
 * 对话任务与导入任务共用“领取/租约/fencing”模式，但两条队列互不混用。</p>
 */
@Component
public class RunQueueSettings {

    @Value("${app.runs.enabled:true}")
    private boolean enabled = true;

    /** 每个进程同时执行的对话运行数（模型调用不在数据库事务里）。 */
    @Value("${app.runs.max-concurrent-runs:2}")
    private int maxConcurrentRuns = 2;

    /** 一次领取的租约时长；运行中每个检查点（模型/工具步骤）续租一次。 */
    @Value("${app.runs.lease-seconds:120}")
    private int leaseSeconds = 120;

    @Value("${app.runs.poll-interval-ms:2000}")
    private long pollIntervalMs = 2_000;

    /** 全局排队上限：超过后提交直接拒绝（429），不把队列当成无限缓存。 */
    @Value("${app.runs.max-queued:200}")
    private int maxQueued = 200;

    /** 同一账号/访客同时排队或执行的任务数上限。 */
    @Value("${app.runs.max-active-per-actor:2}")
    private int maxActivePerActor = 2;

    /** 幂等键长度上限；超长键被拒绝而不是截断。 */
    @Value("${app.runs.max-idempotency-key-length:128}")
    private int maxIdempotencyKeyLength = 128;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int maxConcurrentRuns() {
        return Math.max(1, maxConcurrentRuns);
    }

    public void setMaxConcurrentRuns(int maxConcurrentRuns) {
        this.maxConcurrentRuns = maxConcurrentRuns;
    }

    public int leaseSeconds() {
        return Math.max(10, leaseSeconds);
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public long pollIntervalMs() {
        return Math.max(250, pollIntervalMs);
    }

    public int maxQueued() {
        return Math.max(1, maxQueued);
    }

    public void setMaxQueued(int maxQueued) {
        this.maxQueued = maxQueued;
    }

    public int maxActivePerActor() {
        return Math.max(1, maxActivePerActor);
    }

    public void setMaxActivePerActor(int maxActivePerActor) {
        this.maxActivePerActor = maxActivePerActor;
    }

    public int maxIdempotencyKeyLength() {
        return Math.max(8, maxIdempotencyKeyLength);
    }
}
