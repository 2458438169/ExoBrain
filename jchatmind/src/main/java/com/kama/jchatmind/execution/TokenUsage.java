package com.kama.jchatmind.execution;

/**
 * 服务商返回的真实用量。缺失为 null：不用字符数推算，也不把“未提供”写成 0。
 *
 * <p>汇总规则：每个字段只累加确有提供该字段的调用；{@code total} 只在服务商提供它、或
 * input 与 output 都已知时给出（纯算术，不估算）。Spring AI 在服务商没有返回用量时给出全 0，
 * {@link #of} 会把“全 0 或全空”视作未提供。</p>
 */
public record TokenUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {

    public static final TokenUsage UNKNOWN = new TokenUsage(null, null, null);

    public TokenUsage {
        if (inputTokens != null && inputTokens < 0) {
            throw new IllegalArgumentException("input tokens must not be negative");
        }
        if (outputTokens != null && outputTokens < 0) {
            throw new IllegalArgumentException("output tokens must not be negative");
        }
        if (totalTokens != null && totalTokens < 0) {
            throw new IllegalArgumentException("total tokens must not be negative");
        }
    }

    public boolean known() {
        return inputTokens != null || outputTokens != null || totalTokens != null;
    }

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        Integer input = sum(inputTokens, other.inputTokens);
        Integer output = sum(outputTokens, other.outputTokens);
        Integer total = sum(totalTokens, other.totalTokens);
        if (total == null && input != null && output != null) {
            total = input + output;
        }
        return new TokenUsage(input, output, total);
    }

    /** 只接受服务商给出的数字；全空或全 0 代表没有可用用量，返回 {@link #UNKNOWN}。 */
    public static TokenUsage of(Integer input, Integer output, Integer total) {
        boolean nothingReported = (input == null || input == 0)
                && (output == null || output == 0)
                && (total == null || total == 0);
        if (nothingReported) {
            return UNKNOWN;
        }
        Integer normalizedTotal = total;
        if (normalizedTotal == null && input != null && output != null) {
            normalizedTotal = input + output;
        }
        return new TokenUsage(input, output, normalizedTotal);
    }

    private static Integer sum(Integer first, Integer second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first + second;
    }
}