package com.kama.jchatmind.retrieval;

/**
 * 一次检索只读的索引范围：一个 KB + 一个已发布版本（或 legacy 行）。
 *
 * <p>{@code revisionId == null} 表示 legacy 范围（{@code revision_id IS NULL}），
 * 此时 {@link #indexRevision()} 返回 {@link #LEGACY_REVISION} 占位值——它不是一次已发布索引，
 * 消费方不能把它当成版本号来比较。</p>
 */
public record IndexScope(IndexVersion indexVersion, String revisionId) {

    /** legacy 数据没有发布记录；这是诚实的占位标识，不是伪造的 revisionId。 */
    public static final String LEGACY_REVISION = "legacy-unversioned";

    public static IndexScope legacy() {
        return new IndexScope(IndexVersion.LEGACY_TITLE_V1, null);
    }

    /** 只有 legacy-title-v1 才是 legacy 范围；body-v1 即使还没有已发布版本也不是 legacy 数据。 */
    public boolean legacyScope() {
        return indexVersion == IndexVersion.LEGACY_TITLE_V1;
    }

    public boolean hasPublishedRevision() {
        return revisionId != null;
    }

    public String indexRevision() {
        return revisionId == null ? LEGACY_REVISION : revisionId;
    }
}