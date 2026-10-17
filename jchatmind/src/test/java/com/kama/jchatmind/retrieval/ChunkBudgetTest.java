package com.kama.jchatmind.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 预算单位与夹取规则：单位是 estimated token（保守字符估算），不是精确 tokenizer 计数。 */
class ChunkBudgetTest {

    @Test
    @DisplayName("estimates CJK at 1 estimated token per character and other text at 1 per 2 characters")
    void estimationRuleIsDocumentedAndTested() {
        assertEquals(4, ChunkBudget.estimateTokens("中文测试"));
        assertEquals(2, ChunkBudget.estimateTokens("abcd"));
        assertEquals(3, ChunkBudget.estimateTokens("中文ab"));
        assertEquals(0, ChunkBudget.estimateTokens(""));
        assertEquals(0, ChunkBudget.estimateTokens(null));
    }

    @Test
    @DisplayName("clamps configured values into the development-set range instead of building unbounded chunks")
    void clampsConfiguration() {
        ChunkBudget clamped = ChunkBudget.of(5_000, 500, 999_999);
        assertEquals(ChunkBudget.MAX_TARGET_TOKENS, clamped.targetEstimatedTokens());
        assertEquals(ChunkBudget.MAX_OVERLAP_TOKENS, clamped.overlapEstimatedTokens());
        assertEquals(ChunkBudget.MAX_MAX_CHARACTERS, clamped.maxCharacters());

        ChunkBudget tiny = ChunkBudget.of(1, 1, 1);
        assertEquals(ChunkBudget.MIN_TARGET_TOKENS, tiny.targetEstimatedTokens());
        assertEquals(ChunkBudget.MIN_OVERLAP_TOKENS, tiny.overlapEstimatedTokens());
        assertEquals(ChunkBudget.MIN_MAX_CHARACTERS, tiny.maxCharacters());

        ChunkBudget defaults = ChunkBudget.of(null, null, null);
        assertEquals(ChunkBudget.DEFAULT, defaults);
    }

    @Test
    @DisplayName("refuses a budget whose overlap is not smaller than the target")
    void refusesOverlapAtOrAboveTarget() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkBudget(512, 512, 4096));
        assertThrows(IllegalArgumentException.class, () -> new ChunkBudget(100, 64, 4096));
    }

    @Test
    @DisplayName("fits() applies both the estimated token budget and the character hard cap")
    void appliesBothLimits() {
        ChunkBudget budget = new ChunkBudget(512, 64, 300);
        assertTrue(budget.fits("a".repeat(200)));
        assertTrue(!budget.fits("a".repeat(301)));
        assertTrue(!budget.fits("中".repeat(600)));
    }
}