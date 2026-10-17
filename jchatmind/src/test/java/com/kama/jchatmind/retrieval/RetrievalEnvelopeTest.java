package com.kama.jchatmind.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 契约 §2 的 JSON 字段完整性与状态约束。 */
class RetrievalEnvelopeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RETRIEVAL_ID = "33333333-3333-4333-8333-333333333333";

    private static SourceLocation location() {
        return new SourceLocation("44444444-4444-4444-8444-444444444444", "设计.md", List.of("部署", "网络"),
                "abc123", 7, 9);
    }

    @Test
    @DisplayName("an OK envelope serializes every contractual field with the documented names")
    void okEnvelopeCarriesEveryField() throws Exception {
        RetrievalHit hit = new RetrievalHit(RETRIEVAL_ID + ":1", "55555555-5555-4555-8555-555555555555",
                "66666666-6666-4666-8666-666666666666", 1, "片段正文", location(), "body-v1",
                "77777777-7777-4777-8777-777777777777", RetrievalHit.METRIC_L2, 0.42);

        JsonNode envelope = JSON.readTree(JSON.writeValueAsString(RetrievalEnvelope.ok(RETRIEVAL_ID, List.of(hit))));

        assertEquals(Set.of("schemaVersion", "retrievalId", "status", "hits"), fieldNames(envelope));
        assertEquals(1, envelope.path("schemaVersion").asInt());
        assertEquals(RETRIEVAL_ID, envelope.path("retrievalId").asText());
        assertEquals("OK", envelope.path("status").asText());

        JsonNode jsonHit = envelope.path("hits").get(0);
        assertEquals(Set.of("citationId", "chunkId", "kbId", "rank", "content", "source", "indexVersion",
                "indexRevision", "metric", "distance"), fieldNames(jsonHit));
        assertEquals(RETRIEVAL_ID + ":1", jsonHit.path("citationId").asText());
        assertEquals(1, jsonHit.path("rank").asInt());
        assertEquals("l2", jsonHit.path("metric").asText());
        assertEquals(0.42, jsonHit.path("distance").asDouble(), 1e-9);

        JsonNode source = jsonHit.path("source");
        assertEquals(Set.of("documentId", "documentName", "headingPath", "sourceHash", "startLine", "endLine"),
                fieldNames(source));
        assertEquals(List.of("部署", "网络"), toList(source.path("headingPath")));
        assertEquals(7, source.path("startLine").asInt());
        assertEquals(9, source.path("endLine").asInt());
    }

    @Test
    @DisplayName("legacy hits keep null positions visible as JSON null instead of inventing numbers")
    void legacyPositionsStayNull() throws Exception {
        RetrievalHit legacy = new RetrievalHit(RETRIEVAL_ID + ":1", "55555555-5555-4555-8555-555555555555",
                "66666666-6666-4666-8666-666666666666", 1, "旧片段",
                SourceLocation.withoutPosition("44444444-4444-4444-8444-444444444444", "旧文档.md", List.of()),
                "legacy-title-v1", IndexScope.LEGACY_REVISION, RetrievalHit.METRIC_L2, 1.5);

        JsonNode envelope = JSON.readTree(JSON.writeValueAsString(RetrievalEnvelope.ok(RETRIEVAL_ID, List.of(legacy))));
        JsonNode source = envelope.path("hits").get(0).path("source");
        assertTrue(source.has("sourceHash") && source.get("sourceHash").isNull());
        assertTrue(source.has("startLine") && source.get("startLine").isNull());
        assertTrue(source.has("endLine") && source.get("endLine").isNull());
        assertEquals("legacy-unversioned", envelope.path("hits").get(0).path("indexRevision").asText());
        assertTrue(envelope.path("hits").get(0).path("source").path("headingPath").isEmpty());
    }

    @Test
    @DisplayName("NO_MATCH and UNAVAILABLE carry a safe errorCode, no hits and no confidence field")
    void nonOkStatusesCarryNoHits() throws Exception {
        JsonNode noMatch = JSON.readTree(JSON.writeValueAsString(
                RetrievalEnvelope.noMatch(RETRIEVAL_ID, RetrievalErrorCode.NO_MATCH)));
        assertEquals("NO_MATCH", noMatch.path("status").asText());
        assertEquals(RetrievalErrorCode.NO_MATCH, noMatch.path("errorCode").asText());
        assertTrue(noMatch.path("hits").isArray() && noMatch.path("hits").isEmpty());

        JsonNode unavailable = JSON.readTree(JSON.writeValueAsString(
                RetrievalEnvelope.unavailable(RETRIEVAL_ID, RetrievalErrorCode.EMBEDDING_UNAVAILABLE)));
        assertEquals("UNAVAILABLE", unavailable.path("status").asText());
        assertEquals(RetrievalErrorCode.EMBEDDING_UNAVAILABLE, unavailable.path("errorCode").asText());
        assertTrue(unavailable.path("hits").isEmpty());
        assertFalse(unavailable.has("confidence"));
        assertFalse(unavailable.has("similarity"));
    }

    @Test
    @DisplayName("the envelope refuses combinations that would fake a successful retrieval")
    void refusesInconsistentEnvelopes() {
        RetrievalHit hit = new RetrievalHit(RETRIEVAL_ID + ":1", "chunk", "kb", 1, "content", location(),
                "body-v1", "revision", RetrievalHit.METRIC_L2, 0.1);
        assertThrows(IllegalArgumentException.class,
                () -> new RetrievalEnvelope(1, RETRIEVAL_ID, RetrievalStatus.OK, List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new RetrievalEnvelope(1, RETRIEVAL_ID, RetrievalStatus.NO_MATCH, List.of(hit), "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new RetrievalEnvelope(2, RETRIEVAL_ID, RetrievalStatus.NO_MATCH, List.of(), "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new RetrievalEnvelope(1, RETRIEVAL_ID, RetrievalStatus.OK,
                        List.of(new RetrievalHit("other:1", "chunk", "kb", 1, "c", location(), "v", "r",
                                RetrievalHit.METRIC_L2, 0.1)), null));
        assertThrows(IllegalArgumentException.class,
                () -> new RetrievalHit(RETRIEVAL_ID + ":1", "chunk", "kb", 1, "c", location(), "v", "r",
                        RetrievalHit.METRIC_L2, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new RetrievalHit(RETRIEVAL_ID + ":1", "chunk", "kb", 1, "c", location(), "v", "r",
                        RetrievalHit.METRIC_L2, -0.5));
    }

    private static Set<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names.stream().collect(Collectors.toSet());
    }

    private static List<String> toList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}