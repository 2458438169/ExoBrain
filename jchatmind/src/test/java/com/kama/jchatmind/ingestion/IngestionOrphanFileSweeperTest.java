package com.kama.jchatmind.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 孤儿文件清理：只删除“数据库里从未存在过、也没有任务引用、并且已过宽限期”的文档目录，
 * 不碰仍然存在的文档、KB 级目录、非 UUID 名字与符号链接。
 */
class IngestionOrphanFileSweeperTest {

    @TempDir
    Path storageRoot;

    private final IngestionJobRepository jobs = mock(IngestionJobRepository.class);
    private final IngestionSettings settings = new IngestionSettings();
    private IngestionOrphanFileSweeper sweeper;

    private String kbId;

    @BeforeEach
    void setUp() {
        kbId = UUID.randomUUID().toString();
        sweeper = new IngestionOrphanFileSweeper(jobs, settings);
        ReflectionTestUtils.setField(sweeper, "baseStoragePath", storageRoot.toString());
        settings.setOrphanSweepGraceMinutes(60);
        when(jobs.knowledgeBaseExists(kbId)).thenReturn(true);
        when(jobs.knowledgeBaseExists(anyString())).thenAnswer(invocation -> kbId.equals(invocation.getArgument(0)));
    }

    @Test
    @DisplayName("an orphan document directory older than the grace period is removed")
    void orphanIsRemoved() throws IOException {
        String orphan = UUID.randomUUID().toString();
        Path directory = writeDocument(kbId, orphan, "crashed.md");
        ageDirectory(directory);

        assertEquals(1, sweeper.sweepOnce());
        assertFalse(Files.exists(directory));
    }

    @Test
    @DisplayName("a directory whose document still exists is kept untouched")
    void existingDocumentIsKept() throws IOException {
        String document = UUID.randomUUID().toString();
        Path directory = writeDocument(kbId, document, "keep.md");
        ageDirectory(directory);
        when(jobs.documentExists(kbId, document)).thenReturn(true);

        assertEquals(0, sweeper.sweepOnce());
        assertTrue(Files.exists(directory.resolve("keep.md")));
    }

    @Test
    @DisplayName("a directory still referenced by an ingestion job is kept even without a document row")
    void referencedJobIsKept() throws IOException {
        String document = UUID.randomUUID().toString();
        Path directory = writeDocument(kbId, document, "queued.md");
        ageDirectory(directory);
        when(jobs.hasJobForDocument(document)).thenReturn(true);

        assertEquals(0, sweeper.sweepOnce());
        assertTrue(Files.exists(directory.resolve("queued.md")));
    }

    @Test
    @DisplayName("a fresh directory inside the grace period is kept so an in-flight upload is safe")
    void freshDirectoryIsKept() throws IOException {
        String document = UUID.randomUUID().toString();
        Path directory = writeDocument(kbId, document, "uploading.md");

        assertEquals(0, sweeper.sweepOnce());
        assertTrue(Files.exists(directory.resolve("uploading.md")));
    }

    @Test
    @DisplayName("directories that are not canonical UUIDs and knowledge bases that are gone are skipped")
    void foreignDirectoriesAreSkipped() throws IOException {
        Path keeper = Files.createDirectories(storageRoot.resolve("not-a-uuid"));
        Files.writeString(keeper.resolve("someone.md"), "keep", StandardCharsets.UTF_8);
        Path unknownKb = Files.createDirectories(storageRoot.resolve(UUID.randomUUID().toString()));
        Path document = Files.createDirectories(unknownKb.resolve(UUID.randomUUID().toString()));
        Files.writeString(document.resolve("file.md"), "keep", StandardCharsets.UTF_8);
        ageDirectory(keeper);
        ageDirectory(document);

        assertEquals(0, sweeper.sweepOnce());
        assertTrue(Files.exists(keeper.resolve("someone.md")));
        assertTrue(Files.exists(document.resolve("file.md")));
    }

    @Test
    @DisplayName("the sweeper does nothing when the storage root is absent")
    void missingRootIsSafe() {
        ReflectionTestUtils.setField(sweeper, "baseStoragePath", storageRoot.resolve("absent").toString());
        assertEquals(0, sweeper.sweepOnce());
    }

    private Path writeDocument(String kb, String document, String filename) throws IOException {
        Path directory = Files.createDirectories(storageRoot.resolve(kb).resolve(document));
        Files.writeString(directory.resolve(filename), "# body\n", StandardCharsets.UTF_8);
        return directory;
    }

    private static void ageDirectory(Path directory) throws IOException {
        Instant old = Instant.now().minus(3, ChronoUnit.HOURS);
        Files.setLastModifiedTime(directory, java.nio.file.attribute.FileTime.from(old));
    }
}
