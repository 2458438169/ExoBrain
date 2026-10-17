package com.kama.jchatmind.retrieval;

import java.util.List;

/**
 * 一个待发布的正文 chunk。
 *
 * <p>{@code content} 是交给模型的资料片段；{@code embeddingText} 是真正送去 Embedding 的输入，
 * 明确包含标题路径与实际正文，因此正文事实（标题里没有的信息）也能被检索到。
 * {@code startLine}/{@code endLine} 两端包含，来自标准化文本的真实行号，不是清洗后反推的偏移。</p>
 */
public record ChunkDraft(
        String documentId,
        int ordinal,
        List<String> headingPath,
        int startLine,
        int endLine,
        String sourceHash,
        String content,
        String embeddingText) {

    public ChunkDraft {
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must not be negative");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("invalid source line range " + startLine + ".." + endLine);
        }
    }
}