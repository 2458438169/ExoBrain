package com.kama.jchatmind.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 策略解析的三个分支（契约 §3）。 */
class IndexVersionPolicyTest {

    private static final String KB = "11111111-1111-4111-8111-111111111111";
    private static final String REVISION = "22222222-2222-4222-8222-222222222222";

    @Test
    @DisplayName("a KB with an active state row uses its active_index_version and revision")
    void stateRowWins() {
        KbIndexState state = new KbIndexState(KB, REVISION, IndexVersion.BODY_V1, LocalDateTime.now());
        IndexScope scope = IndexVersionPolicy.resolve(state, false);
        assertEquals(IndexVersion.BODY_V1, scope.indexVersion());
        assertEquals(REVISION, scope.revisionId());
        assertEquals(REVISION, scope.indexRevision());
    }

    @Test
    @DisplayName("a KB without a state row but with legacy chunks stays legacy-title-v1 (never silently switches)")
    void legacyKbStaysLegacy() {
        IndexScope scope = IndexVersionPolicy.resolve(null, true);
        assertEquals(IndexVersion.LEGACY_TITLE_V1, scope.indexVersion());
        assertNull(scope.revisionId());
        assertTrue(scope.legacyScope());
        assertEquals(IndexScope.LEGACY_REVISION, scope.indexRevision());
    }

    @Test
    @DisplayName("an empty KB without a state row uses body-v1")
    void newKbUsesBodyIndex() {
        IndexScope scope = IndexVersionPolicy.resolve(null, false);
        assertEquals(IndexVersion.BODY_V1, scope.indexVersion());
        assertNull(scope.revisionId());
        assertTrue(!scope.legacyScope());
    }

    @Test
    @DisplayName("a state row without an active revision keeps its declared version and reads no children")
    void stateRowWithoutRevision() {
        KbIndexState state = new KbIndexState(KB, null, IndexVersion.BODY_V1, LocalDateTime.now());
        IndexScope scope = IndexVersionPolicy.resolve(state, true);
        assertEquals(IndexVersion.BODY_V1, scope.indexVersion());
        assertNull(scope.revisionId());
        assertTrue(!scope.legacyScope());
    }
}