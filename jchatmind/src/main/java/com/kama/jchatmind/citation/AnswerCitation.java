package com.kama.jchatmind.citation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kama.jchatmind.retrieval.SourceLocation;

/**
 * 回答引用（共享契约 §4）。
 *
 * <p>除 {@code label}/{@code source}/{@code excerpt} 外，其余字段都由服务端从**本次运行实际完成的检索结果**
 * 复制而来：模型只能选择 {@code citationId}，不能决定 documentId、行号、片段内容或跳转 URL。
 * 当某条引用来自公开精选的白名单投影时，被过滤掉的内部字段（{@code citationId}/{@code kbId}/
 * {@code chunkId}/{@code retrievalId}/{@code indexVersion}/{@code indexRevision}）为 {@code null}，
 * 序列化时不会出现（{@link JsonInclude.Include#NON_NULL}），而不是被改写成别的值。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnswerCitation(
        int label,
        String citationId,
        String retrievalId,
        String kbId,
        String chunkId,
        SourceLocation source,
        String indexVersion,
        String indexRevision,
        String excerpt) {

    public AnswerCitation {
        if (label < 1) {
            throw new IllegalArgumentException("citation label starts at 1");
        }
        if (source == null || source.documentId() == null || source.documentId().isBlank()) {
            throw new IllegalArgumentException("a citation needs a source document id");
        }
        if (excerpt == null) {
            throw new IllegalArgumentException("a citation needs an excerpt");
        }
    }
}
