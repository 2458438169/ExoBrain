package com.kama.jchatmind.citation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.retrieval.IndexScope;
import com.kama.jchatmind.retrieval.RetrievalEnvelope;
import com.kama.jchatmind.retrieval.RetrievalErrorCode;
import com.kama.jchatmind.retrieval.RetrievalHit;
import com.kama.jchatmind.retrieval.SourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 来源登记与引用校验的边界：只有本次运行真实完成的 KnowledgeTool 结果能成为来源；模型输入无法伪造位置。
 */
class CitationRegistryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KB = "44444444-4444-4444-8444-444444444444";
    private static final String DOC = "66666666-6666-4666-8666-666666666666";

    @Test
    @DisplayName("only completed KnowledgeTool calls matched by toolCallId can register sources")
    void onlyCompletedKnowledgeToolCallsMatchedByToolCallIdRegister() {
        CitationRegistry registry = new CitationRegistry();
        String envelope = envelope(okEnvelope("ret-1", hit("ret-1:1", "chunk-1", "正文片段", 1, 3, 5)));

        int registered = registry.registerCompletedCalls(List.of(
                // 未在本轮请求过的 toolCallId：忽略
                new CitationRegistry.CompletedCall("other-call", "KnowledgeTool", envelope),
                // 名字不是 KnowledgeTool：即使返回一个完美信封也不登记
                new CitationRegistry.CompletedCall("call-2", "directAnswer",
                        "{\"answer\":\"编造的来源\",\"citationIds\":[\"ret-1:1\"]}"),
                // 其它工具伪装成检索工具的输出（带前缀的自然语言）不是信封
                new CitationRegistry.CompletedCall("call-3", "KnowledgeTool",
                        "工具KnowledgeTool的返回结果为：" + envelope),
                new CitationRegistry.CompletedCall("call-4", "KnowledgeTool", "not json at all"),
                new CitationRegistry.CompletedCall("call-5", "KnowledgeTool",
                        envelope(RetrievalEnvelope.noMatch("ret-x", RetrievalErrorCode.NO_MATCH))),
                new CitationRegistry.CompletedCall("call-6", "KnowledgeTool",
                        envelope(RetrievalEnvelope.unavailable("ret-y", RetrievalErrorCode.RETRIEVAL_UNAVAILABLE))),
                new CitationRegistry.CompletedCall("call-1", "KnowledgeTool", envelope)),

                Set.of("call-1", "call-2", "call-3", "call-4", "call-5", "call-6", "call-7"));

        assertEquals(1, registered, "only the one real OK envelope is registered");
        assertEquals(List.of("ret-1:1"), registry.registered().stream()
                .map(CitationRegistry.RegisteredCitation::citationId).toList());
    }

    @Test
    @DisplayName("a registered citation carries the server fields and never a model supplied position")
    void registeredCitationUsesServerFieldsOnly() {
        CitationRegistry registry = new CitationRegistry();
        RetrievalHit hit = new RetrievalHit("ret-9:2", "chunk-9", KB, 2, "第 4 行到第 6 行的正文",
                new SourceLocation(DOC, "《公开项目介绍》", List.of("架构", "后端"), "abc123", 4, 6),
                "body-v1", "rev-1", RetrievalHit.METRIC_L2, 0.42);
        assertTrue(registry.registerCompletedCalls(
                List.of(new CitationRegistry.CompletedCall("call-1", "KnowledgeTool", envelope(okEnvelope("ret-9", hit)))),
                Set.of("call-1")) == 1);

        CitationRegistry.Resolution resolution = registry.resolve(List.of("ret-9:2"), KB::equals);
        assertEquals(1, resolution.citations().size());
        assertTrue(resolution.notices().isEmpty());
        AnswerCitation citation = resolution.citations().get(0);
        assertEquals(1, citation.label());
        assertEquals("ret-9:2", citation.citationId());
        assertEquals("ret-9", citation.retrievalId());
        assertEquals(KB, citation.kbId());
        assertEquals("chunk-9", citation.chunkId());
        assertEquals("body-v1", citation.indexVersion());
        assertEquals("rev-1", citation.indexRevision());
        assertEquals(DOC, citation.source().documentId());
        assertEquals("《公开项目介绍》", citation.source().documentName());
        assertEquals(List.of("架构", "后端"), citation.source().headingPath());
        assertEquals("abc123", citation.source().sourceHash());
        assertEquals(4, citation.source().startLine());
        assertEquals(6, citation.source().endLine());
        assertEquals("第 4 行到第 6 行的正文", citation.excerpt());
    }

    @Test
    @DisplayName("fabricated, foreign or unauthorized ids produce no clickable citation")
    void unknownOrUnauthorizedIdsProduceNoCitations() {
        CitationRegistry registry = new CitationRegistry();
        CitationRegistry.CompletedCall completedCall = new CitationRegistry.CompletedCall("call-1", "KnowledgeTool",
                envelope(okEnvelope("ret-1", hit("ret-1:1", "chunk-1", "私有资料片段", 1, 2, 4))));
        registry.registerCompletedCalls(List.of(completedCall), Set.of("call-1"));

        // 编造的 id、其它会话/用户的 id、以及本 KB 之外检索得到的 id 都不可能出现在登记表里。
        CitationRegistry.Resolution unknown = registry.resolve(
                List.of("made-up-id", "ret-from-another-session:1", "ret-1:99"), KB::equals);
        assertTrue(unknown.citations().isEmpty());
        assertEquals(CitationRegistry.NoticeReason.UNKNOWN_CITATION_ID,
                unknown.notices().stream().filter(notice -> notice.reason() == CitationRegistry.NoticeReason.UNKNOWN_CITATION_ID)
                        .findFirst().orElseThrow().reason());
        assertEquals(3, noticesOf(unknown).count());

        // 本次运行登记过，但来源已不在获准范围（例如 KB 已不在本次运行的允许集合里）。
        CitationRegistry.Resolution denied = registry.resolve(List.of("ret-1:1"), ignored -> false);
        assertTrue(denied.citations().isEmpty());
        assertEquals(CitationRegistry.NoticeReason.SOURCE_NOT_AUTHORIZED, denied.notices().get(0).reason());
    }

    @Test
    @DisplayName("labels are stable, server ordered, deduplicated per source range")
    void labelsAreStableAndDeduplicated() {
        CitationRegistry registry = new CitationRegistry();
        // 两次真实检索命中同一章节：同一 chunk，不同 retrievalId / citationId。
        registry.registerCompletedCalls(List.of(
                        new CitationRegistry.CompletedCall("call-1", "KnowledgeTool",
                                envelope(okEnvelope("ret-1",
                                        hit("ret-1:1", "chunk-a", "章节 A", 1, 10, 12),
                                        hit("ret-1:2", "chunk-b", "章节 B", 2, 20, 22)))),
                        new CitationRegistry.CompletedCall("call-2", "KnowledgeTool",
                                envelope(okEnvelope("ret-2",
                                        hit("ret-2:1", "chunk-b", "章节 B", 1, 20, 22),
                                        hit("ret-2:2", "chunk-c", "章节 C", 2, 30, 32))))),
                Set.of("call-1", "call-2"));

        // 模型顺序颠倒不影响编号：服务端按 (检索序号, rank) 排序。
        CitationRegistry.Resolution forward = registry.resolve(
                List.of("ret-1:1", "ret-1:2", "ret-2:1", "ret-2:2"), KB::equals);
        CitationRegistry.Resolution reversed = registry.resolve(
                List.of("ret-2:2", "ret-2:1", "ret-1:2", "ret-1:1"), KB::equals);

        assertEquals(3, forward.citations().size(), "chunk-b is one source even when two retrievals return it");
        assertEquals(List.of("chunk-a", "chunk-b", "chunk-c"),
                forward.citations().stream().map(AnswerCitation::chunkId).toList());
        assertEquals(List.of(1, 2, 3), forward.citations().stream().map(AnswerCitation::label).toList());
        assertEquals(forward.citations().stream().map(AnswerCitation::citationId).toList(),
                reversed.citations().stream().map(AnswerCitation::citationId).toList());
        assertEquals(java.util.Map.of(1, 1, 2, 2, 3, 2, 4, 3), forward.labelsByRequestIndex());
        assertEquals(java.util.Map.of(1, 3, 2, 2, 3, 2, 4, 1), reversed.labelsByRequestIndex());
        assertTrue(forward.notices().stream().anyMatch(
                notice -> notice.reason() == CitationRegistry.NoticeReason.DUPLICATE_SOURCE));
    }

    @Test
    @DisplayName("legacy hits without a position stay position-less and dedupe by chunk")
    void legacyHitsKeepNullPositions() {
        CitationRegistry registry = new CitationRegistry();
        RetrievalHit legacy = new RetrievalHit("ret-1:1", "legacy-chunk", KB, 1, "旧片段",
                SourceLocation.withoutPosition(DOC, "公开旧资料", List.of()), "legacy-title-v1",
                IndexScope.LEGACY_REVISION, RetrievalHit.METRIC_L2, 1.25);
        registry.registerCompletedCalls(List.of(new CitationRegistry.CompletedCall("call-1", "KnowledgeTool",
                envelope(okEnvelope("ret-1", legacy)))), Set.of("call-1"));

        CitationRegistry.Resolution resolution = registry.resolve(List.of("ret-1:1"), KB::equals);
        AnswerCitation citation = resolution.citations().get(0);
        assertNull(citation.source().startLine());
        assertNull(citation.source().endLine());
        assertNull(citation.source().sourceHash());
        assertEquals("legacy-unversioned", citation.indexRevision());
    }

    @Test
    @DisplayName("excerpts are bounded excerpts of the real content, and citation count is capped")
    void excerptsAreBoundedAndCountIsCapped() {
        String longContent = " 第一句\t\n第二句 " + "x".repeat(600);
        CitationRegistry registry = new CitationRegistry();
        registry.registerCompletedCalls(List.of(new CitationRegistry.CompletedCall("call-1", "KnowledgeTool",
                envelope(okEnvelope("ret-1",
                        hit("ret-1:1", "chunk-1", longContent, 1, 1, 2),
                        hit("ret-1:2", "chunk-2", "第二段", 2, 3, 4))))), Set.of("call-1"));

        CitationRegistry.Resolution resolution = registry.resolve(List.of("ret-1:1", "ret-1:2"), KB::equals);
        String excerpt = resolution.citations().get(0).excerpt();
        assertTrue(excerpt.length() <= CitationRegistry.EXCERPT_MAX_CHARACTERS);
        assertTrue(excerpt.endsWith("…"));
        assertTrue(excerpt.startsWith("第一句 第二句 x"), excerpt);
        assertFalse(excerpt.contains("\t"));
        assertFalse(excerpt.contains("\n"));

        // 超过上限的引用不会进入元数据，只留下安全的原因码。
        CitationRegistry many = new CitationRegistry();
        many.registerCompletedCalls(List.of(new CitationRegistry.CompletedCall("call-1", "KnowledgeTool",
                envelope(okEnvelope("ret-1", java.util.stream.IntStream.rangeClosed(1, 12)
                        .mapToObj(rank -> hit("ret-1:" + rank, "chunk-" + rank, "片段 " + rank, rank, rank, rank))
                        .toArray(RetrievalHit[]::new))))), Set.of("call-1"));
        CitationRegistry.Resolution capped = many.resolve(
                java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(rank -> "ret-1:" + rank).toList(), KB::equals);
        assertEquals(CitationRegistry.MAX_CITATIONS, capped.citations().size());
        assertEquals(CitationRegistry.MAX_CITATIONS, capped.labelsByRequestIndex().size());
        assertFalse(capped.labelsByRequestIndex().containsKey(11));
        assertTrue(capped.notices().stream().anyMatch(
                notice -> notice.reason() == CitationRegistry.NoticeReason.TOO_MANY_CITATIONS));
    }

    @Test
    @DisplayName("a malformed citationIds argument yields no citation")
    void duplicateIdInTheModelListIsMerged() {
        CitationRegistry registry = new CitationRegistry();
        registry.registerCompletedCalls(List.of(new CitationRegistry.CompletedCall("call-1", "KnowledgeTool",
                envelope(okEnvelope("ret-1", hit("ret-1:1", "chunk-1", "片段", 1, 1, 1))))), Set.of("call-1"));

        CitationRegistry.Resolution resolution = registry.resolve(
                java.util.Arrays.asList("ret-1:1", "ret-1:1", null, " "), KB::equals);
        assertEquals(1, resolution.citations().size());
        assertEquals(List.of(1), resolution.citations().stream().map(AnswerCitation::label).toList());
        assertEquals(java.util.Map.of(1, 1, 2, 1), resolution.labelsByRequestIndex());
        assertTrue(resolution.notices().stream().anyMatch(
                notice -> notice.reason() == CitationRegistry.NoticeReason.DUPLICATE_SOURCE));
        assertTrue(resolution.notices().stream().anyMatch(
                notice -> notice.reason() == CitationRegistry.NoticeReason.MALFORMED_ARGUMENT));
    }

    @Test
    @DisplayName("authorization is mandatory: resolving without a check is a programming error")
    void resolveRequiresAnAuthorizationCheck() {
        CitationRegistry registry = new CitationRegistry();
        assertThrows(NullPointerException.class, () -> registry.resolve(List.of("ret-1:1"), null));
        assertThrows(NullPointerException.class, () -> registry.registerCompletedCalls(null, Set.of()));
        assertTrue(registry.isEmpty());
    }

    // ------------------------------------------------------------------ fixtures

    private static RetrievalEnvelope okEnvelope(String retrievalId, RetrievalHit... hits) {
        return RetrievalEnvelope.ok(retrievalId, List.of(hits));
    }

    private static RetrievalHit hit(String citationId, String chunkId, String content, int rank, int start, int end) {
        return hit(citationId, chunkId, content, rank, start, end, "body-v1", "rev-1");
    }

    private static RetrievalHit hit(String citationId, String chunkId, String content, int rank, int start, int end,
                                    String indexVersion, String indexRevision) {
        return new RetrievalHit(citationId, chunkId, KB, rank, content,
                new SourceLocation(DOC, "《资料》", List.of("章节"), "hash-" + chunkId, start, end),
                indexVersion, indexRevision, RetrievalHit.METRIC_L2, 0.5);
    }

    private static String envelope(RetrievalEnvelope envelope) {
        try {
            return JSON.writeValueAsString(envelope);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static CitationRegistry.Notice noticesOf(CitationRegistry.Resolution resolution) {
        return resolution.notices().get(0);
    }
}
