package com.kama.jchatmind.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 标准化（BOM / CRLF / hash / 行号）与黄金夹具一致性。 */
class SourceTextTest {

    @Test
    @DisplayName("removes a leading BOM and normalizes CRLF/CR to LF")
    void normalizesBomAndLineEndings() {
        String body = "# 标题\n\n正文第一行\n正文第二行\n";
        String bomCrlf = "\uFEFF" + body.replace("\n", "\r\n");
        String crOnly = body.replace("\n", "\r");

        SourceText fromBomCrlf = SourceText.normalize(bomCrlf.getBytes(StandardCharsets.UTF_8));
        SourceText fromCr = SourceText.normalize(crOnly.getBytes(StandardCharsets.UTF_8));
        SourceText plain = SourceText.normalize(body.getBytes(StandardCharsets.UTF_8));

        assertEquals(body, fromBomCrlf.text());
        assertEquals(body, fromCr.text());
        assertEquals(plain.sha256(), fromBomCrlf.sha256());
        assertEquals(plain.sha256(), fromCr.sha256());
        assertFalse(fromBomCrlf.text().contains("\r"));
        assertFalse(fromBomCrlf.text().startsWith("\uFEFF"));
    }

    @Test
    @DisplayName("hash changes when the normalized text changes")
    void hashIsContentAddressed() {
        assertNotEquals(SourceText.normalize(new byte[]{'a', 'b'}).sha256(),
                SourceText.normalize(new byte[]{'a', 'c'}).sha256());
    }

    @Test
    @DisplayName("empty text has zero lines and the well-known SHA-256 of the empty string")
    void emptyTextFacts() {
        SourceText empty = SourceText.normalize(new byte[0]);
        assertEquals(0, empty.lineCount());
        assertEquals("", empty.text());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", empty.sha256());
    }

    @Test
    @DisplayName("line numbers are 1-based, inclusive on both ends and inside the file")
    void lineNumbersAreInclusive() {
        SourceText text = SourceText.normalize("a\nb\nc\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(4, text.lineCount());
        assertEquals(1, text.lineOf(0));
        assertEquals(2, text.lineOf(2));
        assertEquals(4, text.lineOf(6));
        assertEquals(0, text.lineOf(-5) - 1);
        assertEquals(2, text.lineRange(2, 4).startLine());
        assertEquals(2, text.lineRange(2, 4).endLine());
        assertEquals(2, text.lineRange(2, 3).startLine(), "the newline at the end of a line still belongs to it");
        assertEquals(1, text.lineRange(0, 1).startLine());
        assertEquals(1, text.lineRange(0, 1).endLine());
        assertEquals(1, text.lineRange(0, 0).startLine());
        assertThrows(IllegalArgumentException.class, () -> new SourceText.LineRange(2, 1));
    }

    @Test
    @DisplayName("golden fixtures keep their declared sha256 and line counts")
    void goldenFixturesMatchManifest() {
        List<GoldenFixtures.Fixture> fixtures = GoldenFixtures.manifest();
        assertTrue(fixtures.size() >= 8, "the shared golden fixture set must cover the required cases");
        for (GoldenFixtures.Fixture fixture : fixtures) {
            SourceText normalized = SourceText.normalize(GoldenFixtures.rawBytes(fixture.file()));
            assertEquals(fixture.sha256(), normalized.sha256(), fixture.file() + " sha256");
            assertEquals(fixture.lineCount(), normalized.lineCount(), fixture.file() + " lineCount");
        }
    }

    @Test
    @DisplayName("BOM + CRLF fixture and its LF twin are the same source text")
    void bomCrlfFixtureEqualsItsLfTwin() {
        byte[] bomCrlf = GoldenFixtures.rawBytes("bom-crlf.md");
        assertTrue(bomCrlf.length > 3 && (bomCrlf[0] & 0xFF) == 0xEF && (bomCrlf[1] & 0xFF) == 0xBB
                        && (bomCrlf[2] & 0xFF) == 0xBF,
                "bom-crlf.md must really start with a UTF-8 BOM");
        assertTrue(new String(bomCrlf, StandardCharsets.UTF_8).contains("\r\n"),
                "bom-crlf.md must really use CRLF line endings");
        assertEquals(GoldenFixtures.fixture("bom-crlf-lf-twin.md").sha256(),
                SourceText.normalize(bomCrlf).sha256());
        assertEquals(GoldenFixtures.fixture("bom-crlf.md").markers().get("BOM-CRLF-FACT"),
                SourceText.normalize(bomCrlf).lineOf(GoldenFixtures.text("bom-crlf.md")
                        .indexOf("BOM-CRLF-FACT")));
    }

    private static void assertNotEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertNotEquals(expected, actual);
    }
}