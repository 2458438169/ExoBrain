package com.kama.jchatmind.retrieval;

/**
 * 一条检索命中（契约 §2）。{@code metric} 固定为 {@code "l2"}，{@code distance} 是有限非负的 L2 距离，
 * 越小越近，<b>不是概率，也不能用 1 - distance 冒充置信度</b>。
 */
public record RetrievalHit(
        String citationId,
        String chunkId,
        String kbId,
        int rank,
        String content,
        SourceLocation source,
        String indexVersion,
        String indexRevision,
        String metric,
        double distance) {

    public static final String METRIC_L2 = "l2";

    public RetrievalHit {
        if (rank < 1) {
            throw new IllegalArgumentException("rank starts at 1");
        }
        if (!Double.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException("distance must be a finite non-negative L2 distance");
        }
        metric = METRIC_L2;
    }
}