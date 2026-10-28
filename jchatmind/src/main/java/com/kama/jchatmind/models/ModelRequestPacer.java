package com.kama.jchatmind.models;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型请求节流器：保证两次真实模型请求之间至少间隔 {@code app.models.min-interval-ms} 毫秒。
 *
 * <p>为什么需要它：中转站对「短时间内的请求数」有限制，超限时返回**只有状态码 429** 的响应，
 * 既没有 {@code Retry-After} 也没有错误体，客户端无法据此判断是 RPM、TPM 还是余额。
 * 实测（2026-10-28）该限制大约在「同一窗口内第 7 次请求」触发，而每题问答本身要 2–3 次模型调用，
 * 所以只加题间 sleep 覆盖不到一题内的连续请求，必须在请求层面节流。</p>
 *
 * <p>默认值 0 表示不节流（本地单测与默认配置行为不变）；隔离与生产环境按实测限制配置。
 * 这是节奏控制，不是可用率修复：被限流仍按有界退避处理，超过次数就是真实失败。</p>
 */
@Component
public class ModelRequestPacer {

    private final long minIntervalMs;
    private long lastGrantedNanos;

    public ModelRequestPacer(@Value("${app.models.min-interval-ms:0}") long minIntervalMs) {
        this.minIntervalMs = Math.max(0L, minIntervalMs);
    }

    public long minIntervalMs() {
        return minIntervalMs;
    }

    /** 阻塞到允许发出下一次请求为止；等待期间被中断时恢复中断标记并立即返回，不吞掉中断。 */
    public void await() {
        if (minIntervalMs <= 0) {
            return;
        }
        long waitNanos;
        synchronized (this) {
            long now = System.nanoTime();
            long earliest = lastGrantedNanos + minIntervalMs * 1_000_000L;
            waitNanos = earliest - now;
            lastGrantedNanos = Math.max(now, earliest);
        }
        if (waitNanos <= 0) {
            return;
        }
        try {
            Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
