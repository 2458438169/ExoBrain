package com.kama.jchatmind.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 检索与切块配置（{@code app.retrieval.*}）。
 *
 * <ul>
 *   <li>{@code top-k}：每次检索返回的片段数，默认 3，取值夹取到 1–10（契约 §2）。</li>
 *   <li>{@code max-distance}：可选的 L2 拒绝阈值，<b>默认为 null = 未启用</b>。
 *       没有用开发集校准前不设置该值：不假装“80% 相似度”，也不用 {@code 1 - distance} 冒充置信度。
 *       启用后，最相近的片段仍然超过该距离时返回 NO_MATCH，而不是返回无关片段。</li>
 *   <li>{@code chunk-*}：正文切块预算，单位是 estimated token（保守字符估算，见 {@link ChunkBudget}）。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.retrieval")
public class RetrievalSettings {

    public static final int MIN_TOP_K = 1;
    public static final int MAX_TOP_K = 10;
    public static final int DEFAULT_TOP_K = 3;

    private int topK = DEFAULT_TOP_K;
    private Double maxDistance;
    private Integer chunkTargetEstimatedTokens = ChunkBudget.DEFAULT_TARGET_TOKENS;
    private Integer chunkOverlapEstimatedTokens = ChunkBudget.DEFAULT_OVERLAP_TOKENS;
    private Integer maxChunkCharacters = ChunkBudget.DEFAULT_MAX_CHARACTERS;

    public int getTopK() {
        return Math.min(MAX_TOP_K, Math.max(MIN_TOP_K, topK));
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    /** 未配置或配置为 null / 非有限值时返回 null，表示不使用距离阈值。 */
    public Double getMaxDistance() {
        if (maxDistance == null || !Double.isFinite(maxDistance) || maxDistance <= 0) {
            return null;
        }
        return maxDistance;
    }

    public void setMaxDistance(Double maxDistance) {
        this.maxDistance = maxDistance;
    }

    public Integer getChunkTargetEstimatedTokens() {
        return chunkTargetEstimatedTokens;
    }

    public void setChunkTargetEstimatedTokens(Integer chunkTargetEstimatedTokens) {
        this.chunkTargetEstimatedTokens = chunkTargetEstimatedTokens;
    }

    public Integer getChunkOverlapEstimatedTokens() {
        return chunkOverlapEstimatedTokens;
    }

    public void setChunkOverlapEstimatedTokens(Integer chunkOverlapEstimatedTokens) {
        this.chunkOverlapEstimatedTokens = chunkOverlapEstimatedTokens;
    }

    public Integer getMaxChunkCharacters() {
        return maxChunkCharacters;
    }

    public void setMaxChunkCharacters(Integer maxChunkCharacters) {
        this.maxChunkCharacters = maxChunkCharacters;
    }

    public ChunkBudget chunkBudget() {
        return ChunkBudget.of(chunkTargetEstimatedTokens, chunkOverlapEstimatedTokens, maxChunkCharacters);
    }
}