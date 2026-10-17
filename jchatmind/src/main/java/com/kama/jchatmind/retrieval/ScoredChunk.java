package com.kama.jchatmind.retrieval;

/**
 * 向量查询的一行结果（MyBatis 结果对象）：chunk 字段 + 计算出的 L2 距离。
 *
 * <p>只承载数据库返回的原始值；位置与 hash 的解释交给 {@link ChunkMetadata}。</p>
 */
public class ScoredChunk {
    private String id;
    private String kbId;
    private String docId;
    private String content;
    private String metadata;
    private String revisionId;
    private String indexVersion;
    private Integer chunkOrdinal;
    private String sourceHash;
    private Integer startLine;
    private Integer endLine;
    private Double distance;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKbId() {
        return kbId;
    }

    public void setKbId(String kbId) {
        this.kbId = kbId;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getRevisionId() {
        return revisionId;
    }

    public void setRevisionId(String revisionId) {
        this.revisionId = revisionId;
    }

    public String getIndexVersion() {
        return indexVersion;
    }

    public void setIndexVersion(String indexVersion) {
        this.indexVersion = indexVersion;
    }

    public Integer getChunkOrdinal() {
        return chunkOrdinal;
    }

    public void setChunkOrdinal(Integer chunkOrdinal) {
        this.chunkOrdinal = chunkOrdinal;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public void setSourceHash(String sourceHash) {
        this.sourceHash = sourceHash;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public void setStartLine(Integer startLine) {
        this.startLine = startLine;
    }

    public Integer getEndLine() {
        return endLine;
    }

    public void setEndLine(Integer endLine) {
        this.endLine = endLine;
    }

    public Double getDistance() {
        return distance;
    }

    public void setDistance(Double distance) {
        this.distance = distance;
    }
}