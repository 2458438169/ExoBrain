package com.kama.jchatmind.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 有上限的退避与尝试次数（任务书要求“有限重试”）。 */
class IngestionRetryPolicyTest {

    @Test
    @DisplayName("the backoff grows exponentially and stops at the configured maximum")
    void backoffIsBounded() {
        assertEquals(30, IngestionRetryPolicy.delaySeconds(1, 30, 600));
        assertEquals(60, IngestionRetryPolicy.delaySeconds(2, 30, 600));
        assertEquals(120, IngestionRetryPolicy.delaySeconds(3, 30, 600));
        assertEquals(480, IngestionRetryPolicy.delaySeconds(5, 30, 600));
        assertEquals(600, IngestionRetryPolicy.delaySeconds(6, 30, 600));
        assertEquals(600, IngestionRetryPolicy.delaySeconds(50, 30, 600));
    }

    @Test
    @DisplayName("a single attempt configuration still waits at least the base delay and never unbalances")
    void invalidConfigurationIsClamped() {
        assertEquals(1, IngestionRetryPolicy.delaySeconds(0, 0, 0));
        assertEquals(5, IngestionRetryPolicy.delaySeconds(9, 5, 1));
    }

    @Test
    @DisplayName("attempts are consumed and the last allowed attempt is terminal")
    void attemptsAreFinite() {
        assertTrue(IngestionRetryPolicy.hasAttemptsLeft(1, 3));
        assertTrue(IngestionRetryPolicy.hasAttemptsLeft(2, 3));
        assertFalse(IngestionRetryPolicy.hasAttemptsLeft(3, 3));
        assertFalse(IngestionRetryPolicy.hasAttemptsLeft(4, 3));
        assertFalse(IngestionRetryPolicy.hasAttemptsLeft(1, 1));
    }

    @Test
    @DisplayName("only transient codes are retried; permission and resource changes cancel")
    void errorCodesCarryRetrySemantics() {
        assertTrue(IngestionErrorCode.EMBEDDING_UNAVAILABLE.retryable());
        assertTrue(IngestionErrorCode.INDEX_CONFLICT.retryable());
        assertFalse(IngestionErrorCode.EMBEDDING_REJECTED.retryable());
        assertFalse(IngestionErrorCode.NOT_INDEXABLE.retryable());
        assertTrue(IngestionErrorCode.ACCOUNT_DISABLED.cancelling());
        assertTrue(IngestionErrorCode.DOCUMENT_DELETED.cancelling());
        assertTrue(IngestionErrorCode.KNOWLEDGE_BASE_DELETED.cancelling());
        assertFalse(IngestionErrorCode.LEASE_LOST.retryable());
        assertFalse(IngestionErrorCode.LEASE_LOST.cancelling());
    }
}
