package com.kama.jchatmind.retrieval;

/**
 * 参与索引构建的一份源文档快照：标准化后的正文 + 身份。
 *
 * <p>构建候选时读取一次固定内容，候选里携带 {@code source.sha256()}，
 * 发布前重新核对文档清单与源 hash，构建期间的改动会让快照失效。</p>
 */
public record SourceDocument(String documentId, String documentName, SourceText source) {

    public SourceDocument {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId is required");
        }
        if (source == null) {
            throw new IllegalArgumentException("source text is required");
        }
    }

    public String sourceHash() {
        return source.sha256();
    }
}