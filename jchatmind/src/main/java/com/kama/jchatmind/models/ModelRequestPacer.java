package com.kama.jchatmind.models;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型请求节流器：给出「两次真实模型请求之间还需要等多久」。
 *
 * <p>为什么需要它：中转站对「短时间内的请求数」有限制，超限时返回**只有状态码 429** 的响应，
 * 既没有 {@code Retry-After} 也没有错误体，客户端无法据此判断是 RPM、TPM 还是余额。
 * 实测（2026-10-28）该请求节奏下出现过 429，原因类别未知；每题问答本身要 2–3 次模型调用，
 * 所以只加题间 sleep 覆盖不到一题内的连续请求，必须在请求层面控制节奏。</p>
 *
 * <p>默认值 0 表示不节流（本地单测与默认配置行为不变）；隔离环境按实测限制配置。
 * 这是节奏控制，不是可用率修复：被限流仍按有界退避处理，超过次数就是真实失败。</p>
 *
 * <p><b>这里不做等待。</b>等待由调用方（{@code JChatMind}）分片执行，以便在等待期间响应取消、
 * 租约失效与线程中断；见 {@link ModelRequestGate}。</p>
 */
@Component
public class ModelRequestPacer implements ModelRequestGate {

    private final long minIntervalMs;
    private long lastReservedNanos;

    public ModelRequestPacer(@Value("${app.models.min-interval-ms:0}") long minIntervalMs) {
        this.minIntervalMs = Math.max(0L, minIntervalMs);
    }

    public long minIntervalMs() {
        return minIntervalMs;
    }

    @Override
    public synchronized long reserveDelayNanos() {
        if (minIntervalMs <= 0) {
            return 0L;
        }
        long now = System.nanoTime();
        long earliest = lastReservedNanos + minIntervalMs * 1_000_000L;
        long delay = earliest - now;
        lastReservedNanos = Math.max(now, earliest);
        return Math.max(0L, delay);
    }
}
