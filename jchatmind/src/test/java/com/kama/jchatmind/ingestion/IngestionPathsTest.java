package com.kama.jchatmind.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** worker 读取文件时的路径边界：只接受 kbId/documentId/filename 形状，拒绝任何请求式路径。 */
class IngestionPathsTest {

    private static final String KB = "11111111-1111-4111-8111-111111111111";
    private static final String DOC = "22222222-2222-4222-8222-222222222222";

    @Test
    @DisplayName("a three component path whose ids match the job is accepted")
    void validPathIsAccepted() {
        Optional<String> path = IngestionJobRunner.validRelativePath(
                KB + "/" + DOC + "/file.md", KB, DOC);
        assertTrue(path.isPresent());
        assertEquals(KB + "/" + DOC + "/file.md", path.get());
    }

    @Test
    @DisplayName("absolute paths, backslashes, drive letters and extra levels are rejected")
    void unsafePathsAreRejected() {
        assertTrue(IngestionJobRunner.validRelativePath("C:/tmp/file.md", KB, DOC).isEmpty());
        assertTrue(IngestionJobRunner.validRelativePath("/etc/passwd.md", KB, DOC).isEmpty());
        assertTrue(IngestionJobRunner.validRelativePath(KB + "\\" + DOC + "\\file.md", KB, DOC).isEmpty());
        assertTrue(IngestionJobRunner.validRelativePath(KB + "/" + DOC + "/../file.md", KB, DOC).isEmpty());
        assertTrue(IngestionJobRunner.validRelativePath(KB + "/file.md", KB, DOC).isEmpty());
        assertTrue(IngestionJobRunner.validRelativePath(KB + "/" + DOC + "/sub/file.md", KB, DOC).isEmpty());
        assertTrue(IngestionJobRunner.validRelativePath(null, KB, DOC).isEmpty());
    }

    @Test
    @DisplayName("a path belonging to another document or knowledge base is rejected")
    void foreignPathsAreRejected() {
        String otherDoc = "33333333-3333-4333-8333-333333333333";
        String otherKb = "44444444-4444-4444-8444-444444444444";
        assertTrue(IngestionJobRunner.validRelativePath(otherKb + "/" + DOC + "/file.md", KB, DOC).isEmpty());
        assertTrue(IngestionJobRunner.validRelativePath(KB + "/" + otherDoc + "/file.md", KB, DOC).isEmpty());
        assertTrue(IngestionJobRunner.validRelativePath(KB + "/" + DOC + "/file.md", "not-a-uuid", DOC).isEmpty());
    }

    @Test
    @DisplayName("only markdown files can be read by the worker")
    void onlyMarkdownIsReadable() {
        assertTrue(IngestionJobRunner.validRelativePath(KB + "/" + DOC + "/file.md", KB, DOC).isPresent());
        assertTrue(IngestionJobRunner.validRelativePath(KB + "/" + DOC + "/file.markdown", KB, DOC).isPresent());
        assertTrue(IngestionJobRunner.validRelativePath(KB + "/" + DOC + "/file.MD", KB, DOC).isPresent());
        assertTrue(IngestionJobRunner.validRelativePath(KB + "/" + DOC + "/file.pdf", KB, DOC).isEmpty());
    }
}
