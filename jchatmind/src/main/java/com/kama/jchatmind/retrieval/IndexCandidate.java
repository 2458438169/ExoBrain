package com.kama.jchatmind.retrieval;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选索引：构建期间与活动版本完全隔离的中间产物。
 *
 * <p>{@code sources} 是候选覆盖的文档清单（文档 id + 源 hash），{@code chunks} 是本次真正要写入的
 * 正文 chunk；上传路径只对新文档切块，未变化文档的 chunk 在发布时从 {@code baseRevisionId} 指向的
 * 活动版本在库内复制（复制向量不调用外部服务）。Embedding 结果由 {@link #withEmbeddings(List)} 附加，
 * 外部服务调用永远发生在数据库事务之外。</p>
 */
public record IndexCandidate(
        String kbId,
        IndexVersion indexVersion,
        String modelCatalogId,
        String modelName,
        int dimensions,
        List<SourceRef> sources,
        List<ChunkDraft> chunks,
        List<float[]> embeddings,
        String baseRevisionId,
        String sourceSetHash) {

    public IndexCandidate {
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("kbId is required");
        }
        if (indexVersion == null) {
            throw new IllegalArgumentException("indexVersion is required");
        }
        sources = sources == null ? List.of() : List.copyOf(sources);
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        embeddings = embeddings == null ? null : List.copyOf(embeddings);
        if (embeddings != null && embeddings.size() != chunks.size()) {
            throw new IllegalArgumentException("every chunk needs exactly one embedding");
        }
    }

    public boolean embeddingsAttached() {
        return embeddings != null;
    }

    public IndexCandidate withEmbeddings(List<float[]> vectors) {
        return new IndexCandidate(kbId, indexVersion, modelCatalogId, modelName, dimensions, sources, chunks,
                vectors, baseRevisionId, sourceSetHash);
    }

    public List<String> sourceDocumentIds() {
        List<String> ids = new ArrayList<>();
        for (SourceRef source : sources) {
            ids.add(source.documentId());
        }
        return ids;
    }

    /** 本次要写入的 chunk 覆盖的文档 id。 */
    public List<String> chunkDocumentIds() {
        List<String> ids = new ArrayList<>();
        for (ChunkDraft chunk : chunks) {
            if (!ids.contains(chunk.documentId())) {
                ids.add(chunk.documentId());
            }
        }
        return ids;
    }
}