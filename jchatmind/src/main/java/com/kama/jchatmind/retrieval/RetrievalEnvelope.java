package com.kama.jchatmind.retrieval;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 检索结果信封（契约 §2，schemaVersion = 1）。
 *
 * <p>{@code retrievalId} 表示一次实际检索，{@code citationId = retrievalId + ":" + rank} 由服务端生成。
 * {@code NO_MATCH} 与 {@code UNAVAILABLE} 的 hits 必须为空，构造时强制校验。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RetrievalEnvelope(
        int schemaVersion,
        String retrievalId,
        RetrievalStatus status,
        List<RetrievalHit> hits,
        String errorCode) {

    public static final int SCHEMA_VERSION = 1;

    public RetrievalEnvelope {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion " + schemaVersion);
        }
        if (retrievalId == null || retrievalId.isBlank()) {
            throw new IllegalArgumentException("retrievalId is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        hits = hits == null ? List.of() : List.copyOf(hits);
        if (status == RetrievalStatus.OK && hits.isEmpty()) {
            throw new IllegalArgumentException("OK requires at least one hit");
        }
        if (status != RetrievalStatus.OK && !hits.isEmpty()) {
            throw new IllegalArgumentException(status + " must not carry hits");
        }
        for (RetrievalHit hit : hits) {
            if (hit.citationId() == null || !hit.citationId().startsWith(retrievalId + ":")) {
                throw new IllegalArgumentException("citationId must belong to this retrievalId");
            }
        }
    }

    public static RetrievalEnvelope ok(String retrievalId, List<RetrievalHit> hits) {
        return new RetrievalEnvelope(SCHEMA_VERSION, retrievalId, RetrievalStatus.OK, hits, null);
    }

    public static RetrievalEnvelope noMatch(String retrievalId, String errorCode) {
        return new RetrievalEnvelope(SCHEMA_VERSION, retrievalId, RetrievalStatus.NO_MATCH, List.of(), errorCode);
    }

    public static RetrievalEnvelope unavailable(String retrievalId, String errorCode) {
        return new RetrievalEnvelope(SCHEMA_VERSION, retrievalId, RetrievalStatus.UNAVAILABLE, List.of(), errorCode);
    }
}