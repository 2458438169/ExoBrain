package com.kama.jchatmind.retrieval;

/**
 * 索引策略解析（写清楚并可测试，契约 §3）。
 *
 * <ol>
 *   <li>KB 有活动状态行 → 用其 {@code active_index_version} 与 {@code active_revision_id}。</li>
 *   <li>无状态行、但该 KB 已有 chunk（legacy 行）→ 保持 {@code legacy-title-v1}，<b>不静默切换</b>。</li>
 *   <li>无状态行且没有任何 chunk（新库）→ {@code body-v1}。</li>
 * </ol>
 *
 * <p>状态行只在候选原子发布成功时写入，所以“没有状态行”= 这个库从未成功发布过正文索引。</p>
 */
public final class IndexVersionPolicy {

    private IndexVersionPolicy() {
    }

    public static IndexScope resolve(KbIndexState state, boolean hasLegacyChunks) {
        if (state != null && state.activeIndexVersion() != null) {
            return new IndexScope(state.activeIndexVersion(), state.activeRevisionId());
        }
        if (hasLegacyChunks) {
            return IndexScope.legacy();
        }
        return new IndexScope(IndexVersion.BODY_V1, null);
    }
}