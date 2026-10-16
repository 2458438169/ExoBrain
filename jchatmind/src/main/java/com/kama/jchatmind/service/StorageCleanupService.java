package com.kama.jchatmind.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

/** Commit database deletion first; retry physical cleanup durably without trusting file metadata. */
@Service
@Slf4j
public class StorageCleanupService {
    private final JdbcTemplate jdbc;
    private final DocumentStorageService storage;
    private final TransactionTemplate transaction;

    public StorageCleanupService(JdbcTemplate jdbc, DocumentStorageService storage, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void enqueueKnowledgeBase(String kbId) { enqueue("KB", kbId, kbId); }
    public void enqueueDocument(String kbId, String documentId) { enqueue("DOCUMENT", documentId, kbId); }

    private void enqueue(String kind, String resourceId, String kbId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Storage cleanup must be queued in the deletion transaction");
        Job job = new Job(kind, UUID.fromString(resourceId).toString(), UUID.fromString(kbId).toString());
        jdbc.update("INSERT INTO exobrain_storage_cleanup(kind,resource_id,kb_id) VALUES (?,CAST(? AS uuid),CAST(? AS uuid)) "
                + "ON CONFLICT DO NOTHING", job.kind(), job.id(), job.kbId());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { clean(job); }
        });
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void retryPending() {
        List<Job> jobs = jdbc.query("SELECT kind,resource_id::text,kb_id::text FROM exobrain_storage_cleanup "
                + "ORDER BY created_at LIMIT 100", (row, index) -> new Job(row.getString(1), row.getString(2), row.getString(3)));
        jobs.forEach(this::clean);
    }

    private void clean(Job job) {
        try {
            transaction.executeWithoutResult(status -> {
                List<String> locked = jdbc.queryForList("SELECT resource_id::text FROM exobrain_storage_cleanup "
                        + "WHERE kind=? AND resource_id=CAST(? AS uuid) FOR UPDATE SKIP LOCKED",
                        String.class, job.kind(), job.id());
                if (locked.isEmpty()) return;
                String table = job.kind().equals("KB") ? "knowledge_base" : "document";
                Integer remaining = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id=CAST(? AS uuid)",
                        Integer.class, job.id());
                if (remaining == null || remaining != 0)
                    throw new IllegalStateException("Refusing to clean storage for an existing resource");
                try {
                    if (job.kind().equals("KB")) storage.deleteKnowledgeBaseFiles(job.kbId());
                    else storage.deleteDocumentFiles(job.kbId(), job.id());
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
                jdbc.update("DELETE FROM exobrain_storage_cleanup WHERE kind=? AND resource_id=CAST(? AS uuid)",
                        job.kind(), job.id());
            });
        } catch (RuntimeException failure) {
            // The durable job survives errors and restarts; deleted content is already unreachable by every API.
            log.warn("Storage cleanup queued for retry: kind={}, resourceId={}", job.kind(), job.id(), failure);
        }
    }

    private record Job(String kind, String id, String kbId) { }
}
