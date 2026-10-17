package com.kama.jchatmind.retrieval;

/** （文档, 源 hash）引用：用于比较候选与活动版本的文档清单。 */
public record SourceRef(String documentId, String sourceHash) {
}