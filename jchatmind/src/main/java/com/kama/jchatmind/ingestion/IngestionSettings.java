package com.kama.jchatmind.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 导入 worker 与提交路径的边界配置。默认值即生产默认；部署时通过 {@code app.ingestion.*} 覆盖
 * （键名与默认值见 docs/development/deliveries/06.md 第 8 节，08 统一登记）。
 *
 * <p>所有上限都是“有界”的：并发数、文件大小、chunk 数、尝试次数与退避上限，避免一个坏文档
 * 或慢端点无限占用 worker、无限重试或无限付费调用 Embedding。</p>
 */
@Component
public class IngestionSettings {

    @Value("${app.ingestion.enabled:true}")
    private boolean enabled = true;

    /** 同一进程内同时执行的任务数（多实例部署时每个实例各自计数）。 */
    @Value("${app.ingestion.max-concurrent-jobs:2}")
    private int maxConcurrentJobs = 2;

    /** 一次领取的租约时长；worker 每完成一个 chunk 续租一次。 */
    @Value("${app.ingestion.lease-seconds:120}")
    private int leaseSeconds = 120;

    @Value("${app.ingestion.max-attempts:3}")
    private int maxAttempts = 3;

    @Value("${app.ingestion.retry-base-seconds:30}")
    private int retryBaseSeconds = 30;

    /** 退避上限：第 n 次失败等待 min(base * 2^(n-1), max)。 */
    @Value("${app.ingestion.retry-max-seconds:600}")
    private int retryMaxSeconds = 600;

    @Value("${app.ingestion.max-file-bytes:5242880}")
    private long maxFileBytes = 5L * 1024 * 1024;

    /** 单份文档允许的最大 chunk 数，超过直接失败而不是无限切块。 */
    @Value("${app.ingestion.max-chunks:500}")
    private int maxChunks = 500;

    @Value("${app.ingestion.poll-interval-ms:5000}")
    private long pollIntervalMs = 5_000;

    /** 孤儿文件清理：上传崩溃窗口留下的文件目录超过该宽限期才会被删除。 */
    @Value("${app.ingestion.orphan-sweep-enabled:true}")
    private boolean orphanSweepEnabled = true;

    @Value("${app.ingestion.orphan-sweep-grace-minutes:60}")
    private int orphanSweepGraceMinutes = 60;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int maxConcurrentJobs() {
        return Math.max(1, maxConcurrentJobs);
    }

    public void setMaxConcurrentJobs(int maxConcurrentJobs) {
        this.maxConcurrentJobs = maxConcurrentJobs;
    }

    public int leaseSeconds() {
        return Math.max(10, leaseSeconds);
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public int maxAttempts() {
        return Math.max(1, maxAttempts);
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int retryBaseSeconds() {
        return Math.max(1, retryBaseSeconds);
    }

    public void setRetryBaseSeconds(int retryBaseSeconds) {
        this.retryBaseSeconds = retryBaseSeconds;
    }

    public int retryMaxSeconds() {
        return Math.max(retryBaseSeconds(), retryMaxSeconds);
    }

    public void setRetryMaxSeconds(int retryMaxSeconds) {
        this.retryMaxSeconds = retryMaxSeconds;
    }

    public long maxFileBytes() {
        return Math.max(1, maxFileBytes);
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public int maxChunks() {
        return Math.max(1, maxChunks);
    }

    public void setMaxChunks(int maxChunks) {
        this.maxChunks = maxChunks;
    }

    public long pollIntervalMs() {
        return Math.max(250, pollIntervalMs);
    }

    public boolean orphanSweepEnabled() {
        return orphanSweepEnabled;
    }

    public void setOrphanSweepEnabled(boolean orphanSweepEnabled) {
        this.orphanSweepEnabled = orphanSweepEnabled;
    }

    public int orphanSweepGraceMinutes() {
        return Math.max(1, orphanSweepGraceMinutes);
    }

    public void setOrphanSweepGraceMinutes(int orphanSweepGraceMinutes) {
        this.orphanSweepGraceMinutes = orphanSweepGraceMinutes;
    }
}
