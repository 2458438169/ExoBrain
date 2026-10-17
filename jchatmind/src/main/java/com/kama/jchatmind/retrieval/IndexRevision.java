package com.kama.jchatmind.retrieval;

import java.time.LocalDateTime;

/**
 * 一次索引发布记录（表 {@code exobrain_index_revision}）。
 *
 * <p>状态机：DRAFT（已登记、候选未发布）→ PUBLISHED（当前活动版本）→ SUPERSEDED（被新版本替换）；
 * 构建失败可登记 FAILED。DRAFT / FAILED 行不参与检索。</p>
 */
public record IndexRevision(
        String id,
        String kbId,
        IndexVersion indexVersion,
        IndexStatus status,
        String modelCatalogId,
        String modelName,
        int dimensions,
        int documentCount,
        int chunkCount,
        String sourceSetHash,
        LocalDateTime createdAt,
        LocalDateTime publishedAt,
        LocalDateTime supersededAt) {

    public enum IndexStatus {
        DRAFT, PUBLISHED, SUPERSEDED, FAILED
    }
}
