package com.kama.jchatmind.retrieval;

/**
 * 正文切块预算。
 *
 * <p><b>单位说明（重要）：</b>本类里的 “token” 一律是 {@code estimatedTokens}——
 * 用保守字符估算得到的近似值，<b>不是</b> bge-m3 tokenizer 的精确计数：
 * CJK 字符按 1 estimated token/字符，其余字符按 1 estimated token/2 字符（偏保守的上界近似）。
 * 因此中文文档的实际 token 数只会小于或等于该估算，不会更多；把中文字符数当精确 token 是错的。</p>
 *
 * <p>{@code maxCharacters} 是每个 chunk 的硬上限（字符数，默认 4096），保证不会无限膨胀到模型限制之外；
 * 它先于 token 预算生效。开发集起点：目标 512–800 estimated tokens、重叠 64–100 estimated tokens。</p>
 */
public record ChunkBudget(int targetEstimatedTokens, int overlapEstimatedTokens, int maxCharacters) {

    public static final int MIN_TARGET_TOKENS = 512;
    public static final int MAX_TARGET_TOKENS = 800;
    public static final int MIN_OVERLAP_TOKENS = 64;
    public static final int MAX_OVERLAP_TOKENS = 100;
    public static final int DEFAULT_TARGET_TOKENS = 640;
    public static final int DEFAULT_OVERLAP_TOKENS = 80;
    public static final int DEFAULT_MAX_CHARACTERS = 4096;
    public static final int MIN_MAX_CHARACTERS = 256;
    public static final int MAX_MAX_CHARACTERS = 16384;

    public static final ChunkBudget DEFAULT =
            new ChunkBudget(DEFAULT_TARGET_TOKENS, DEFAULT_OVERLAP_TOKENS, DEFAULT_MAX_CHARACTERS);

    public ChunkBudget {
        if (targetEstimatedTokens < MIN_TARGET_TOKENS || targetEstimatedTokens > MAX_TARGET_TOKENS) {
            throw new IllegalArgumentException("targetEstimatedTokens must stay within "
                    + MIN_TARGET_TOKENS + ".." + MAX_TARGET_TOKENS + " estimated tokens");
        }
        if (overlapEstimatedTokens < MIN_OVERLAP_TOKENS || overlapEstimatedTokens > MAX_OVERLAP_TOKENS
                || overlapEstimatedTokens >= targetEstimatedTokens) {
            throw new IllegalArgumentException("overlapEstimatedTokens must stay within "
                    + MIN_OVERLAP_TOKENS + ".." + MAX_OVERLAP_TOKENS + " and below the target");
        }
        if (maxCharacters < MIN_MAX_CHARACTERS || maxCharacters > MAX_MAX_CHARACTERS) {
            throw new IllegalArgumentException("maxCharacters must stay within "
                    + MIN_MAX_CHARACTERS + ".." + MAX_MAX_CHARACTERS);
        }
    }

    /** 由配置构造；未配置或越界的值夹取到开发集起点范围内，避免一份错误配置产出无界 chunk。 */
    public static ChunkBudget of(Integer target, Integer overlap, Integer maxCharacters) {
        int effectiveTarget = target == null ? DEFAULT_TARGET_TOKENS : clamp(target, MIN_TARGET_TOKENS, MAX_TARGET_TOKENS);
        int effectiveOverlap = overlap == null ? DEFAULT_OVERLAP_TOKENS : clamp(overlap, MIN_OVERLAP_TOKENS, MAX_OVERLAP_TOKENS);
        if (effectiveOverlap >= effectiveTarget) {
            effectiveOverlap = Math.max(MIN_OVERLAP_TOKENS, effectiveTarget / 8);
        }
        int effectiveCharacters = maxCharacters == null ? DEFAULT_MAX_CHARACTERS
                : clamp(maxCharacters, MIN_MAX_CHARACTERS, MAX_MAX_CHARACTERS);
        return new ChunkBudget(effectiveTarget, effectiveOverlap, effectiveCharacters);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    /** 保守字符估算，见类注释：CJK 1 estimated token/字符，其余 1 estimated token/2 字符。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            }
        }
        int other = text.length() - cjk;
        return cjk + (other + 1) / 2;
    }

    private static boolean isCjk(char character) {
        return (character >= 0x2E80 && character <= 0x9FFF)
                || (character >= 0xF900 && character <= 0xFAFF)
                || (character >= 0xFF00 && character <= 0xFF60)
                || (character >= 0x3000 && character <= 0x303F);
    }

    /** 文本是否同时满足字符硬上限与目标 token 预算（重叠预算另算）。 */
    public boolean fits(String text) {
        return text.length() <= maxCharacters && estimateTokens(text) <= targetEstimatedTokens;
    }
}