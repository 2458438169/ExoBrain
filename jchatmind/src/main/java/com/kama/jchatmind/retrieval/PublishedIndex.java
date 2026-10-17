package com.kama.jchatmind.retrieval;

/** 一次成功发布的索引版本及其检索范围。 */
public record PublishedIndex(IndexRevision revision, IndexScope scope, int documentCount, int chunkCount) {
}