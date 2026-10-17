package com.kama.jchatmind.retrieval;

import java.time.LocalDateTime;

/**
 * 每个知识库的当前活动索引（表 {@code exobrain_kb_index_state}）。
 *
 * <p>没有状态行时按 {@link IndexVersionPolicy} 推导：已有 chunk 的旧库保持 legacy，
 * 空库使用 body-v1。状态行只在候选原子发布成功时写入。</p>
 */
public record KbIndexState(String kbId, String activeRevisionId, IndexVersion activeIndexVersion,
                           LocalDateTime updatedAt) {
}
