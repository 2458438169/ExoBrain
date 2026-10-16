package com.kama.jchatmind.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StorageCleanupServiceTest {
    private static final String KB = "11111111-1111-4111-8111-111111111111";
    private static final String DOC = "22222222-2222-4222-8222-222222222222";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DocumentStorageService storage = mock(DocumentStorageService.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private StorageCleanupService cleanup;

    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        cleanup = new StorageCleanupService(jdbc, storage, manager);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("DOCUMENT"), eq(DOC))).thenReturn(List.of(DOC));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(DOC))).thenReturn(0);
    }

    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }

    @Test void storageIsUntouchedUntilTheDatabaseDeletionCommits() throws Exception {
        cleanup.enqueueDocument(KB, DOC);
        verifyNoInteractions(storage);
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
        verify(storage).deleteDocumentFiles(KB, DOC);
        verify(jdbc).update(startsWith("DELETE FROM exobrain_storage_cleanup"), eq("DOCUMENT"), eq(DOC));
    }

    @Test void databaseRollbackDoesNotDeleteOriginalFiles() {
        cleanup.enqueueKnowledgeBase(KB);
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verifyNoInteractions(storage);
        verify(jdbc, never()).update(startsWith("DELETE FROM exobrain_storage_cleanup"), any(), any());
    }

    @Test void deletionMustNotQueueOutsideTheOwningDatabaseTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class, () -> cleanup.enqueueDocument(KB, DOC));
        verifyNoInteractions(storage);
        verify(jdbc, never()).update(startsWith("INSERT"), any(), any(), any());
    }

    @Test void cleanupFailureRemainsQueuedAndARepeatCanFinishIt() throws Exception {
        doThrow(new IOException("temporary storage failure")).doNothing().when(storage).deleteDocumentFiles(KB, DOC);
        cleanup.enqueueDocument(KB, DOC);
        TransactionSynchronization callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
        assertDoesNotThrow(callback::afterCommit);
        verify(jdbc, never()).update(startsWith("DELETE FROM exobrain_storage_cleanup"), any(), any());

        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn("DOCUMENT");
        when(row.getString(2)).thenReturn(DOC);
        when(row.getString(3)).thenReturn(KB);
        doAnswer(call -> {
            RowMapper<?> mapper = call.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(startsWith("SELECT kind,"), any(RowMapper.class));
        cleanup.retryPending();
        verify(storage, times(2)).deleteDocumentFiles(KB, DOC);
        verify(jdbc).update(startsWith("DELETE FROM exobrain_storage_cleanup"), eq("DOCUMENT"), eq(DOC));
    }

    @Test void staleCleanupJobCannotDeleteFilesOfARestoredResource() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(DOC))).thenReturn(1);
        cleanup.enqueueDocument(KB, DOC);
        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit());
        verifyNoInteractions(storage);
        verify(jdbc, never()).update(startsWith("DELETE FROM exobrain_storage_cleanup"), any(), any());
    }
}
