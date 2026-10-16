package com.kama.jchatmind.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DocumentStorageCleanupTest {
    private static final String KB = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER = "22222222-2222-4222-8222-222222222222";
    private static final String DOC = "33333333-3333-4333-8333-333333333333";
    @TempDir Path root;
    private DocumentStorageServiceImpl storage;

    @BeforeEach void setup() {
        storage = new DocumentStorageServiceImpl();
        ReflectionTestUtils.setField(storage, "baseStoragePath", root.toString());
    }

    @Test void knowledgeBaseDeletionRemovesItsOriginalFilesAndPreservesOtherAccountsFiles() throws Exception {
        Path own = write(KB, DOC, "private content");
        Path other = write(OTHER, DOC, "other account content");
        storage.deleteKnowledgeBaseFiles(KB);
        assertFalse(Files.exists(own));
        assertFalse(Files.exists(root.resolve(KB)));
        assertEquals("other account content", Files.readString(other));
        storage.deleteKnowledgeBaseFiles(KB);
        assertTrue(Files.isDirectory(root));
    }

    @Test void documentDeletionPreservesSiblingDocumentsAndDoesNotNeedStoredMetadata() throws Exception {
        Path own = write(KB, DOC, "deleted");
        Path sibling = write(KB, OTHER, "preserved");
        storage.deleteDocumentFiles(KB, DOC);
        assertFalse(Files.exists(own));
        assertEquals("preserved", Files.readString(sibling));
        storage.deleteDocumentFiles(KB, DOC);
    }

    @Test void invalidIdentifiersCannotEscapeTheStorageRoot() throws Exception {
        Path other = write(OTHER, DOC, "preserved");
        for (String invalid : new String[]{"..", "../" + OTHER, "/tmp", "C:\\", "1-1-1-1-1"}) {
            assertThrows(IllegalArgumentException.class, () -> storage.deleteKnowledgeBaseFiles(invalid));
            assertThrows(IllegalArgumentException.class, () -> storage.deleteDocumentFiles(KB, invalid));
        }
        assertEquals("preserved", Files.readString(other));
    }

    @Test void symbolicLinksInsideDeletedKnowledgeBaseCannotDeleteAnotherKnowledgeBase() throws Exception {
        Path peer = write(OTHER, DOC, "preserved");
        Files.createDirectories(root.resolve(KB));
        try { Files.createSymbolicLink(root.resolve(KB).resolve(DOC), peer.getParent()); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic links unavailable on this test host");
        }
        storage.deleteKnowledgeBaseFiles(KB);
        assertFalse(Files.exists(root.resolve(KB)));
        assertEquals("preserved", Files.readString(peer));
    }

    private Path write(String kb, String doc, String value) throws IOException {
        Path path = root.resolve(kb).resolve(doc).resolve("notes.md");
        Files.createDirectories(path.getParent());
        return Files.writeString(path, value);
    }
}
