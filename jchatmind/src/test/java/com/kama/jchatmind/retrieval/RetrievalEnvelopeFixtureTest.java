package com.kama.jchatmind.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 给 04 的四类合成响应夹具（成功 / 无匹配 / 故障 / legacy 无位置），位置：
 * {@code src/test/resources/retrieval-golden/envelopes/}。
 *
 * <p>夹具反过来校验契约：能被反序列化成 {@link RetrievalEnvelope}（构造器会拒绝非法组合），
 * 字段名与契约 §2 一致，非 OK 状态没有 hits，legacy 位置保持 null。</p>
 */
class RetrievalEnvelopeFixtureTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIRECTORY = "retrieval-golden/envelopes/";
    private static final List<String> FIXTURES = List.of("ok.json", "no-match.json", "unavailable.json",
            "legacy-no-position.json");

    private static JsonNode read(String file) throws Exception {
        try (InputStream stream = RetrievalEnvelopeFixtureTest.class.getClassLoader()
                .getResourceAsStream(DIRECTORY + file)) {
            assertNotNull(stream, "missing fixture " + file);
            JsonNode node = JSON.readTree(stream);
            // 反序列化会走构造器校验：夹具本身必须是一份合法的契约响应。
            JSON.treeToValue(node, RetrievalEnvelope.class);
            return node;
        }
    }

    @Test
    @DisplayName("the four synthetic responses are contract-legal and keep stable identifiers")
    void fixturesAreContractLegal() throws Exception {
        for (String file : FIXTURES) {
            JsonNode envelope = read(file);
            assertEquals(1, envelope.path("schemaVersion").asInt(), file);
            assertFalse(envelope.path("retrievalId").asText().isBlank(), file);
            assertTrue(envelope.path("hits").isArray(), file);
        }
    }

    @Test
    @DisplayName("the success fixture carries two ranked hits with source locations and L2 distances")
    void successFixtureCarriesLocations() throws Exception {
        JsonNode envelope = read("ok.json");
        assertEquals("OK", envelope.path("status").asText());
        assertEquals(2, envelope.path("hits").size());
        JsonNode first = envelope.path("hits").get(0);
        assertEquals(1, first.path("rank").asInt());
        assertEquals(envelope.path("retrievalId").asText() + ":1", first.path("citationId").asText());
        assertEquals("l2", first.path("metric").asText());
        assertTrue(first.path("distance").asDouble() >= 0);
        assertEquals("body-v1", first.path("indexVersion").asText());
        List<String> headingPath = new java.util.ArrayList<>();
        first.path("source").path("headingPath").forEach(node -> headingPath.add(node.asText()));
        assertEquals(List.of("部署说明", "网络入口"), headingPath);
        assertTrue(first.path("source").path("startLine").asInt() <= first.path("source").path("endLine").asInt());
    }

    @Test
    @DisplayName("no-match and unavailable fixtures carry a safe error code and no hits")
    void failureFixturesCarryNoHits() throws Exception {
        JsonNode noMatch = read("no-match.json");
        assertEquals("NO_MATCH", noMatch.path("status").asText());
        assertEquals("NO_MATCH", noMatch.path("errorCode").asText());
        assertTrue(noMatch.path("hits").isEmpty());

        JsonNode unavailable = read("unavailable.json");
        assertEquals("UNAVAILABLE", unavailable.path("status").asText());
        assertEquals("EMBEDDING_UNAVAILABLE", unavailable.path("errorCode").asText());
        assertTrue(unavailable.path("hits").isEmpty());
    }

    @Test
    @DisplayName("the legacy fixture keeps null positions instead of fabricated line numbers")
    void legacyFixtureKeepsNullPositions() throws Exception {
        JsonNode legacy = read("legacy-no-position.json");
        JsonNode hit = legacy.path("hits").get(0);
        assertEquals("legacy-title-v1", hit.path("indexVersion").asText());
        assertEquals("legacy-unversioned", hit.path("indexRevision").asText());
        assertTrue(hit.path("source").get("sourceHash").isNull());
        assertTrue(hit.path("source").get("startLine").isNull());
        assertTrue(hit.path("source").get("endLine").isNull());
        assertTrue(hit.path("source").path("headingPath").isEmpty());
    }
}