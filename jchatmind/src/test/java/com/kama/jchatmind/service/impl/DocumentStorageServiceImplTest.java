package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.DocumentContentException;
import com.kama.jchatmind.service.DocumentStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DocumentStorageServiceImplTest {
    private static final String KB = "11111111-1111-4111-8111-111111111111";
    private static final String DOCUMENT = "22222222-2222-4222-8222-222222222222";
    private static final String OTHER_KB = "33333333-3333-4333-8333-333333333333";
    private static final String OTHER_DOCUMENT = "44444444-4444-4444-8444-444444444444";
    private static final String RELATIVE = KB + "/" + DOCUMENT + "/original.md";

    @TempDir Path temporaryDirectory;
    private Path root;
    private DocumentStorageServiceImpl storage;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(temporaryDirectory.resolve("storage"));
        storage = new DocumentStorageServiceImpl();
        ReflectionTestUtils.setField(storage, "baseStoragePath", root.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"md", "MD", "markdown", "MarkDown"})
    void readsSavedOriginalUtf8AndStripsOnlyLeadingBom(String extension) throws IOException {
        String content = "# 中文标题 🚀\r\n\r\n```java\r\nvar x = 1;\r\n```\r\n\n保留 \ufeff 正文与空行\n";
        var upload = new MockMultipartFile("file", "说明." + extension, "text/markdown",
                ("\ufeff" + content).getBytes(StandardCharsets.UTF_8));
        String path = storage.saveFile(KB, DOCUMENT, upload);

        assertEquals(content, storage.readMarkdown(KB, DOCUMENT, path));
    }

    @Test
    void permitsExactlyTwoMiBButRejectsOneAdditionalByte() throws IOException {
        String content = "a".repeat(DocumentStorageService.MAX_PREVIEW_BYTES);
        Path file = write(RELATIVE, content.getBytes(StandardCharsets.UTF_8));
        assertEquals(content, storage.readMarkdown(KB, DOCUMENT, RELATIVE));

        Files.write(file, new byte[DocumentStorageService.MAX_PREVIEW_BYTES + 1]);
        assertFailure(413, RELATIVE);
    }

    @Test
    void rejectsInvalidUtf8InsteadOfSilentlyReplacingBytes() throws IOException {
        write(RELATIVE, new byte[]{'#', ' ', (byte) 0xc3, 0x28});
        assertFailure(422, RELATIVE);
    }

    @Test
    void rejectsNonMarkdownStorageFile() throws IOException {
        String relative = KB + "/" + DOCUMENT + "/original.pdf";
        write(relative, "private bytes".getBytes(StandardCharsets.UTF_8));
        assertFailure(415, relative);
    }

    @Test
    void rejectsMissingOriginalAndDirectoryMasqueradingAsMarkdown() throws IOException {
        assertFailure(404, RELATIVE);
        Files.createDirectories(root.resolve(RELATIVE));
        assertFailure(404, RELATIVE);
    }

    @Test
    void rejectsAbsoluteTraversalCrossDocumentAndCrossKnowledgeBasePaths() throws IOException {
        Path original = write(RELATIVE, "# original".getBytes(StandardCharsets.UTF_8));
        String otherDocument = KB + "/" + OTHER_DOCUMENT + "/private.md";
        String otherKb = OTHER_KB + "/" + DOCUMENT + "/private.md";
        write(otherDocument, "cross-document secret".getBytes(StandardCharsets.UTF_8));
        write(otherKb, "cross-KB secret".getBytes(StandardCharsets.UTF_8));
        Files.writeString(temporaryDirectory.resolve("outside.md"), "outside secret", StandardCharsets.UTF_8);
        for (String path : new String[]{original.toString(), "../outside.md", "C:/private/secret.md",
                "\\\\server\\private\\secret.md", KB + "/" + DOCUMENT + "/../../../outside.md",
                KB + "/" + DOCUMENT + "/../" + OTHER_DOCUMENT + "/private.md", otherDocument, otherKb,
                KB + "/" + DOCUMENT + "/original.md:secret.md", KB + "/" + DOCUMENT + "/nested/file.md"}) {
            assertFailure(404, path);
        }
    }

    @Test
    void rejectsIdsThatCouldChangeTheExpectedDirectory() throws IOException {
        write(RELATIVE, "# original".getBytes(StandardCharsets.UTF_8));
        for (String id : new String[]{"..", "/tmp", "1-1-1-1-1", "", null}) {
            assertEquals(404, assertThrows(DocumentContentException.class,
                    () -> storage.readMarkdown(id, DOCUMENT, RELATIVE)).getStatus());
            assertEquals(404, assertThrows(DocumentContentException.class,
                    () -> storage.readMarkdown(KB, id, RELATIVE)).getStatus());
        }
    }

    @Test
    void rejectsFileSymlinkOutsideTheDocumentDirectory() throws IOException {
        Path target = Files.writeString(temporaryDirectory.resolve("private.md"), "outside secret");
        Files.createDirectories(root.resolve(KB).resolve(DOCUMENT));
        createSymlink(root.resolve(RELATIVE), target);
        assertFailure(404, RELATIVE);
    }

    @Test
    void rejectsDocumentDirectorySymlinkIntoAnotherDocument() throws IOException {
        Path target = write(KB + "/" + OTHER_DOCUMENT + "/original.md",
                "cross-document secret".getBytes(StandardCharsets.UTF_8)).getParent();
        createSymlink(root.resolve(KB).resolve(DOCUMENT), target);
        assertFailure(404, RELATIVE);
    }

    @Test
    void rejectsKnowledgeBaseDirectorySymlinkIntoAnotherKnowledgeBase() throws IOException {
        write(OTHER_KB + "/" + DOCUMENT + "/original.md", "cross-KB secret".getBytes(StandardCharsets.UTF_8));
        createSymlink(root.resolve(KB), root.resolve(OTHER_KB));
        assertFailure(404, RELATIVE);
    }

    private Path write(String relative, byte[] content) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        return Files.write(target, content);
    }

    private void assertFailure(int status, String filePath) {
        DocumentContentException error = assertThrows(DocumentContentException.class,
                () -> storage.readMarkdown(KB, DOCUMENT, filePath));
        assertEquals(status, error.getStatus());
        assertFalse(error.getMessage().contains(root.toString()));
        assertFalse(error.getMessage().contains("secret"));
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException unsupported) {
            assumeTrue(false, "Filesystem does not support symbolic links");
        } catch (IOException unavailable) {
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) throw unavailable;
            assumeTrue(false, "Windows cannot create symbolic links in this test environment");
        }
    }
}
