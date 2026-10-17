package com.kama.jchatmind.ingestion;

/** 有上限的指数退避：第 n 次失败等待 {@code min(base * 2^(n-1), max)} 秒。 */
public final class IngestionRetryPolicy {

    private IngestionRetryPolicy() {
    }

    public static int delaySeconds(int attempts, int baseSeconds, int maxSeconds) {
        int base = Math.max(1, baseSeconds);
        int max = Math.max(base, maxSeconds);
        int completed = Math.max(1, attempts);
        long delay = (long) base << Math.min(20, completed - 1);
        return (int) Math.min(max, Math.max(base, delay));
    }

    /**
     * 用掉一次尝试后是否还允许继续：尝试次数达到上限即进入终态，不再自动重试
     * （避免坏文档或停用的端点在后台无限重试与无限付费）。
     */
    public static boolean hasAttemptsLeft(int attemptsSoFar, int maxAttempts) {
        return attemptsSoFar < Math.max(1, maxAttempts);
    }
}
