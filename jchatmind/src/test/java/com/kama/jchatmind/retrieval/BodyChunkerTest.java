package com.kama.jchatmind.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 正文切块的全部边界：首标题前正文、无标题、超长章节、重复标题、中文、代码块、表格、空文件。 */
class BodyChunkerTest {

    private static final String DOCUMENT = "11111111-1111-4111-8111-111111111111";
    private static final BodyChunker CHUNKER = new BodyChunker();
    private static final ChunkBudget BUDGET = ChunkBudget.DEFAULT;

    private static SourceDocument document(String file) {
        return new SourceDocument(DOCUMENT, file, SourceText.normalize(GoldenFixtures.rawBytes(file)));
    }

    private static List<ChunkDraft> chunk(String file) {
        return CHUNKER.chunk(document(file), BUDGET);
    }

    @Test
    @DisplayName("keeps prose before the first heading, with an empty heading path")
    void coversPreambleBeforeFirstHeading() {
        List<ChunkDraft> chunks = chunk("preamble.md");
        ChunkDraft preamble = chunks.stream()
                .filter(chunk -> chunk.content().contains("PREAMBLE-FACT")).findFirst().orElseThrow();
        assertEquals(List.of(), preamble.headingPath());
        assertEquals(1, preamble.startLine());
        assertTrue(preamble.content().contains("开头正文"));
        // 第一个标题之后的正文归属该标题，路径不再为空。
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.headingPath().equals(List.of("正式章节"))));
        assertCoversEveryNonBlankLine(document("preamble.md"), chunks);
    }

    @Test
    @DisplayName("chunks Markdown without any heading")
    void chunksMarkdownWithoutHeading() {
        List<ChunkDraft> chunks = chunk("plain-no-heading.md");
        assertEquals(1, chunks.size());
        assertEquals(List.of(), chunks.get(0).headingPath());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(5, chunks.get(0).endLine());
        assertTrue(chunks.get(0).content().contains("PLAIN-FACT"));
        assertEquals(GoldenFixtures.fixture("plain-no-heading.md").markers().get("PLAIN-FACT"),
                chunks.get(0).startLine() + 2);
    }

    @Test
    @DisplayName("splits an oversized section into bounded chunks that overlap and keep accurate ranges")
    void splitsOversizedSectionWithOverlap() {
        SourceDocument document = document("long-section.md");
        List<ChunkDraft> chunks = CHUNKER.chunk(document, BUDGET);
        assertTrue(chunks.size() >= 6, "a 126-line section must not fit one chunk: " + chunks.size());

        int midFactLine = GoldenFixtures.fixture("long-section.md").markers().get("LONG-MID-FACT");
        int tailFactLine = GoldenFixtures.fixture("long-section.md").markers().get("LONG-TAIL-FACT");
        ChunkDraft mid = chunkCovering(chunks, midFactLine);
        ChunkDraft tail = chunkCovering(chunks, tailFactLine);
        assertTrue(mid.content().contains("LONG-MID-FACT"));
        assertTrue(tail.content().contains("LONG-TAIL-FACT"));
        assertEquals(List.of("长章节", "过程"), tail.headingPath());
        assertTrue(tail.endLine() >= tailFactLine);

        for (int index = 1; index < chunks.size(); index++) {
            assertTrue(chunks.get(index).startLine() <= chunks.get(index - 1).endLine(),
                    "consecutive chunks must overlap: " + index);
        }
        assertCoversEveryNonBlankLine(document, chunks);
    }

    @Test
    @DisplayName("distinguishes duplicate headings by source range and keeps both bodies")
    void distinguishesDuplicateHeadings() {
        List<ChunkDraft> chunks = chunk("duplicate-headings.md");
        int dupB = GoldenFixtures.fixture("duplicate-headings.md").markers().get("DUP-B");
        int dupC = GoldenFixtures.fixture("duplicate-headings.md").markers().get("DUP-C");
        ChunkDraft chunkB = chunkCovering(chunks, dupB);
        ChunkDraft chunkC = chunkCovering(chunks, dupC);
        assertEquals(List.of("客户端", "参数"), chunkB.headingPath());
        assertEquals(List.of("客户端", "参数"), chunkC.headingPath());
        assertTrue(chunkB.content().contains("DUP-B"));
        assertTrue(chunkC.content().contains("DUP-C"));
        assertFalse(chunkB.content().contains("DUP-C"));
        assertFalse(chunkC.content().contains("DUP-B"));
        assertTrue(chunkB.endLine() < chunkC.startLine(), "same title, different source ranges");
    }

    @Test
    @DisplayName("keeps Chinese chunks inside the conservative estimate budget")
    void keepsChineseChunksInsideBudget() {
        SourceDocument document = document("chinese.md");
        List<ChunkDraft> chunks = CHUNKER.chunk(document, BUDGET);
        assertTrue(!chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("CN-FACT")));
        for (ChunkDraft chunk : chunks) {
            assertWithinBudget(chunk, document);
            assertTrue(chunk.content().contains("中文") || chunk.content().contains("estimated"));
        }
    }

    @Test
    @DisplayName("bounded splitting for a long code fence, with the trailing fact still located")
    void splitsLongCodeBlockWithAccurateRange() {
        SourceDocument document = document("code-block.md");
        List<ChunkDraft> chunks = CHUNKER.chunk(document, BUDGET);
        assertTrue(chunks.size() >= 2, "the 82-line fence must be split: " + chunks.size());
        int factLine = GoldenFixtures.fixture("code-block.md").markers().get("CODE-FACT");
        ChunkDraft fact = chunkCovering(chunks, factLine);
        assertTrue(fact.content().contains("CODE-FACT"));
        assertEquals(List.of("代码块"), fact.headingPath());
        for (ChunkDraft chunk : chunks) {
            assertWithinBudget(chunk, document);
            assertTrue(chunk.content().length() <= BUDGET.maxCharacters());
        }
        assertCoversEveryNonBlankLine(document, chunks);
    }

    @Test
    @DisplayName("bounded splitting for a long table, with the last row still located")
    void splitsLongTableWithAccurateRange() {
        SourceDocument document = document("table.md");
        List<ChunkDraft> chunks = CHUNKER.chunk(document, BUDGET);
        assertTrue(chunks.size() >= 2, "a 65-line table must be split: " + chunks.size());
        int factLine = GoldenFixtures.fixture("table.md").markers().get("TABLE-FACT");
        ChunkDraft fact = chunkCovering(chunks, factLine);
        assertTrue(fact.content().contains("TABLE-FACT"));
        assertTrue(fact.endLine() >= factLine);
        for (ChunkDraft chunk : chunks) {
            assertWithinBudget(chunk, document);
        }
        assertCoversEveryNonBlankLine(document, chunks);
    }

    @Test
    @DisplayName("an empty file produces no chunks instead of an empty one")
    void emptyFileProducesNoChunks() {
        assertEquals(List.of(), chunk("empty.md"));
        assertEquals(List.of(), CHUNKER.chunk(new SourceDocument(DOCUMENT, "blank.md",
                SourceText.normalize(new byte[0])), BUDGET));
    }

    @Test
    @DisplayName("every chunk carries the source hash, a valid line range and a sequential ordinal")
    void chunksCarryStableIdentity() {
        SourceDocument document = document("long-section.md");
        List<ChunkDraft> chunks = CHUNKER.chunk(document, BUDGET);
        for (int index = 0; index < chunks.size(); index++) {
            ChunkDraft chunk = chunks.get(index);
            assertEquals(index, chunk.ordinal());
            assertEquals(document.sourceHash(), chunk.sourceHash());
            assertEquals(DOCUMENT, chunk.documentId());
            assertTrue(chunk.startLine() >= 1);
            assertTrue(chunk.endLine() >= chunk.startLine());
            assertTrue(chunk.endLine() <= document.source().lineCount());
            assertTrue(chunk.embeddingText().contains(chunk.content()));
            assertWithinBudget(chunk, document);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ChunkDraft chunkCovering(List<ChunkDraft> chunks, int line) {
        List<ChunkDraft> matches = new ArrayList<>();
        for (ChunkDraft chunk : chunks) {
            if (chunk.startLine() <= line && line <= chunk.endLine()) {
                matches.add(chunk);
            }
        }
        assertFalse(matches.isEmpty(), "no chunk covers line " + line);
        return matches.get(0);
    }

    private static void assertWithinBudget(ChunkDraft chunk, SourceDocument document) {
        assertTrue(BUDGET.estimateTokens(chunk.content()) <= BUDGET.targetEstimatedTokens(),
                "chunk " + chunk.ordinal() + " exceeds the estimated token budget");
        assertTrue(chunk.content().length() <= BUDGET.maxCharacters());
        String[] lines = document.source().text().split("\n", -1);
        int from = offsetOfLine(lines, chunk.startLine());
        int to = offsetOfLine(lines, chunk.endLine()) + lines[chunk.endLine() - 1].length();
        assertTrue(document.source().text().substring(from, to).contains(chunk.content().strip()),
                "claimed line range must cover the chunk content (chunk " + chunk.ordinal() + ")");
    }

    /**
     * 每个非空正文行都必须落在某个 chunk 的来源范围内（正文不会被无标识地丢掉）。
     * 标题行本身不作为 chunk 正文：它们以标题路径的形式进入 Embedding 输入。
     */
    private static void assertCoversEveryNonBlankLine(SourceDocument document, List<ChunkDraft> chunks) {
        String[] lines = document.source().text().split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].isBlank() || lines[index].stripLeading().startsWith("#")) {
                continue;
            }
            int line = index + 1;
            boolean covered = chunks.stream().anyMatch(chunk -> chunk.startLine() <= line && line <= chunk.endLine());
            assertTrue(covered, "line " + line + " is not covered by any chunk");
        }
    }

    private static int offsetOfLine(String[] lines, int line) {
        int offset = 0;
        for (int index = 0; index < line - 1; index++) {
            offset += lines[index].length() + 1;
        }
        return offset;
    }
}