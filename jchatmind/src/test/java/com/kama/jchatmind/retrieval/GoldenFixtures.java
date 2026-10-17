package com.kama.jchatmind.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 共享黄金夹具（{@code src/test/resources/retrieval-golden/}）的读取入口，03 与 04 复用同一份。
 *
 * <p>夹具是事实基准：{@code manifest.json} 记录标准化后的 sha256、行数与事实标记行号；
 * 测试只读取与断言，不按实现方便修改夹具。</p>
 */
final class GoldenFixtures {

    static final String DIRECTORY = "retrieval-golden";
    private static final ObjectMapper JSON = new ObjectMapper();

    private GoldenFixtures() {
    }

    static byte[] rawBytes(String file) {
        try (InputStream stream = GoldenFixtures.class.getClassLoader()
                .getResourceAsStream(DIRECTORY + "/" + file)) {
            if (stream == null) {
                throw new IllegalStateException("missing golden fixture " + file);
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    static String text(String file) {
        return new String(rawBytes(file), StandardCharsets.UTF_8);
    }

    /** manifest.json 里的一份夹具事实。 */
    record Fixture(String file, String encoding, String sha256, int lineCount, List<List<String>> headings,
                   Map<String, Integer> markers) {
    }

    static List<Fixture> manifest() {
        JsonNode root;
        try (InputStream stream = GoldenFixtures.class.getClassLoader()
                .getResourceAsStream(DIRECTORY + "/manifest.json")) {
            if (stream == null) {
                throw new IllegalStateException("missing retrieval-golden/manifest.json");
            }
            root = JSON.readTree(stream);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        List<Fixture> fixtures = new ArrayList<>();
        for (JsonNode fixture : root.path("fixtures")) {
            List<List<String>> headings = new ArrayList<>();
            for (JsonNode heading : fixture.path("headings")) {
                if (heading.isArray()) {
                    List<String> path = new ArrayList<>();
                    heading.forEach(part -> path.add(part.asText()));
                    headings.add(path);
                } else {
                    headings.add(List.of(heading.asText()));
                }
            }
            Map<String, Integer> markers = new LinkedHashMap<>();
            fixture.path("markers").fields().forEachRemaining(
                    entry -> markers.put(entry.getKey(), entry.getValue().asInt()));
            fixtures.add(new Fixture(fixture.path("file").asText(), fixture.path("encoding").asText(),
                    fixture.path("sha256").asText(), fixture.path("lineCount").asInt(), headings, markers));
        }
        return fixtures;
    }

    static Fixture fixture(String file) {
        return manifest().stream().filter(entry -> entry.file().equals(file)).findFirst()
                .orElseThrow(() -> new IllegalStateException("fixture not declared in manifest: " + file));
    }
}