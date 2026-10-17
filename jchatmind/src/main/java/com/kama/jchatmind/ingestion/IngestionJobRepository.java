package com.kama.jchatmind.ingestion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 导入任务的读写（表 {@code exobrain_ingestion_job}，迁移 {@code deploy/migrations/20261017-06-ingestion.sql}）。
 *
 * <p>并发约定：</p>
 * <ul>
 *   <li>领取是一条语句（{@code FOR UPDATE SKIP LOCKED} + {@code UPDATE ... RETURNING}），因此不需要长事务，
 *       多实例/多 worker 竞争同一任务时只有一个能拿到租约。</li>
 *   <li>所有状态写回都带 {@code fencing_token} 与 {@code status='RUNNING'} 条件：租约过期后被重新领取的旧
 *       worker 即使晚到结果，也写不回任何状态。</li>
 * </ul>
 *
 * <p>这里不处理文件删除：资源已删除后的物理清理仍由 {@code StorageCleanupService} 负责，两张队列不混用。</p>
 */
@Repository
public class IngestionJobRepository {

    private static final String COLUMNS = "id, kb_id, document_id, file_path, source_hash, actor_user_id, status, "
            + "attempts, max_attempts, next_attempt_at, lease_owner, lease_expires_at, fencing_token, "
            + "index_revision_id, chunks_done, chunks_total, error_code, error_message, created_at, started_at, "
            + "finished_at";

    private static final RowMapper<IngestionJob> ROW = IngestionJobRepository::map;

    private final JdbcTemplate jdbc;

    public IngestionJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static IngestionJob map(ResultSet rs, int row) throws SQLException {
        return new IngestionJob(
                rs.getString("id"), rs.getString("kb_id"), rs.getString("document_id"), rs.getString("file_path"),
                rs.getString("source_hash"), rs.getString("actor_user_id"),
                IngestionStatus.fromWire(rs.getString("status")), rs.getInt("attempts"), rs.getInt("max_attempts"),
                instant(rs, "next_attempt_at"), rs.getString("lease_owner"), instant(rs, "lease_expires_at"),
                rs.getLong("fencing_token"), rs.getString("index_revision_id"), rs.getInt("chunks_done"),
                (Integer) rs.getObject("chunks_total"), rs.getString("error_code"), rs.getString("error_message"),
                instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public String insert(String kbId, String documentId, String filePath, String sourceHash, String actorUserId,
                         int maxAttempts) {
        return jdbc.queryForObject("INSERT INTO exobrain_ingestion_job "
                        + "(kb_id, document_id, file_path, source_hash, actor_user_id, status, max_attempts) "
                        + "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, CAST(? AS uuid), ?, ?) RETURNING id",
                String.class, kbId, documentId, filePath, sourceHash, actorUserId,
                IngestionStatus.QUEUED.name(), Math.max(1, maxAttempts));
    }

    public Optional<IngestionJob> findById(String jobId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exobrain_ingestion_job WHERE id = CAST(? AS uuid)",
                ROW, jobId).stream().findFirst();
    }

    public Optional<IngestionJob> findLatestForDocument(String documentId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exobrain_ingestion_job "
                        + "WHERE document_id = CAST(? AS uuid) ORDER BY created_at DESC, id DESC LIMIT 1",
                ROW, documentId).stream().findFirst();
    }

    /** KB 下每个文档的最新任务（文档列表一次查询即可带上状态，避免每个文档各轮询一次）。 */
    public Map<String, IngestionJob> latestForKnowledgeBase(String kbId) {
        List<IngestionJob> jobs = jdbc.query("SELECT DISTINCT ON (document_id) " + COLUMNS
                        + " FROM exobrain_ingestion_job WHERE kb_id = CAST(? AS uuid) "
                        + "ORDER BY document_id, created_at DESC, id DESC", ROW, kbId);
        return jobs.stream().collect(Collectors.toMap(job -> job.documentId().toLowerCase(Locale.ROOT),
                job -> job, (first, second) -> first));
    }

    /**
    /**
     * 短事务领取：只取到期的 {@code QUEUED}/{@code RETRY_WAIT}，或租约已过期的 {@code RUNNING}
     * （重启/崩溃恢复）。尝试次数达到上限的任务不领取（由 {@link #failExhausted} 收尾）。
     */
    public Optional<IngestionJob> claim(String leaseOwner, int leaseSeconds) {
        return jdbc.query("WITH candidate AS ("
                        + " SELECT id FROM exobrain_ingestion_job"
                        + " WHERE (status IN (?, ?) AND next_attempt_at <= NOW())"
                        + "    OR (status = ? AND lease_expires_at IS NOT NULL AND lease_expires_at < NOW())"
                        + " ORDER BY next_attempt_at, created_at"
                        + " FOR UPDATE SKIP LOCKED LIMIT 1)"
                        + " UPDATE exobrain_ingestion_job job SET"
                        + "  status = ?, attempts = job.attempts + 1,"
                        + "  lease_owner = ?, lease_expires_at = NOW() + (? * INTERVAL '1 second'),"
                        + "  fencing_token = job.fencing_token + 1,"
                        + "  started_at = COALESCE(job.started_at, NOW()), updated_at = NOW()"
                        + " FROM candidate WHERE job.id = candidate.id"
                        + " RETURNING job.id, job.kb_id, job.document_id, job.file_path, job.source_hash,"
                        + " job.actor_user_id, job.status, job.attempts, job.max_attempts, job.next_attempt_at,"
                        + " job.lease_owner, job.lease_expires_at, job.fencing_token, job.index_revision_id,"
                        + " job.chunks_done, job.chunks_total, job.error_code, job.error_message, job.created_at,"
                        + " job.started_at, job.finished_at",
                ROW, IngestionStatus.QUEUED.name(), IngestionStatus.RETRY_WAIT.name(),
                IngestionStatus.RUNNING.name(), IngestionStatus.RUNNING.name(),
                leaseOwner, Math.max(1, leaseSeconds)).stream().findFirst();
    }

    /** 发布闸门使用的加锁读：必须在调用方事务内执行，行锁持有到事务结束。 */
    public Optional<IngestionJob> lockById(String jobId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exobrain_ingestion_job WHERE id = CAST(? AS uuid) FOR UPDATE",
                ROW, jobId).stream().findFirst();
    }

    /** 续租并记录真实进度；返回 false 表示租约已被别的 worker 拿走（fencing 拒绝旧 worker 继续）。 */
    public boolean renew(String jobId, long fencingToken, int chunksDone, Integer chunksTotal, int leaseSeconds) {
        return jdbc.update("UPDATE exobrain_ingestion_job SET"
                        + " lease_expires_at = NOW() + (? * INTERVAL '1 second'), chunks_done = ?,"
                        + " chunks_total = COALESCE(?, chunks_total), updated_at = NOW()"
                        + " WHERE id = CAST(? AS uuid) AND fencing_token = ? AND status = ?"
                        + " AND lease_expires_at > NOW()",
                Math.max(1, leaseSeconds), Math.max(0, chunksDone), chunksTotal, jobId, fencingToken,
                IngestionStatus.RUNNING.name()) > 0;
    }

    public boolean setChunksTotal(String jobId, long fencingToken, int chunksTotal) {
        return jdbc.update("UPDATE exobrain_ingestion_job SET chunks_total = ?, chunks_done = 0, updated_at = NOW()"
                        + " WHERE id = CAST(? AS uuid) AND fencing_token = ? AND status = ?",
                Math.max(0, chunksTotal), jobId, fencingToken, IngestionStatus.RUNNING.name()) > 0;
    }

    public boolean succeed(String jobId, long fencingToken, String revisionId, int chunkCount) {
        return jdbc.update("UPDATE exobrain_ingestion_job SET status = ?, chunks_done = ?, chunks_total = ?,"
                        + " index_revision_id = CAST(? AS uuid), error_code = NULL, error_message = NULL,"
                        + " lease_owner = NULL, lease_expires_at = NULL, finished_at = NOW(), updated_at = NOW()"
                        + " WHERE id = CAST(? AS uuid) AND fencing_token = ? AND status = ?",
                IngestionStatus.SUCCEEDED.name(), chunkCount, chunkCount, revisionId, jobId, fencingToken,
                IngestionStatus.RUNNING.name()) > 0;
    }

    /** 终态（FAILED / CANCELLED）；同样受 fencing 约束。 */
    public boolean finish(String jobId, long fencingToken, IngestionStatus status, IngestionErrorCode code) {
        if (!status.isTerminal()) {
            throw new IllegalArgumentException("finish 只接受终态");
        }
        return jdbc.update("UPDATE exobrain_ingestion_job SET status = ?, error_code = ?, error_message = ?,"
                        + " lease_owner = NULL, lease_expires_at = NULL, finished_at = NOW(), updated_at = NOW()"
                        + " WHERE id = CAST(? AS uuid) AND fencing_token = ? AND status = ?",
                status.name(), code.name(), code.safeMessage(), jobId, fencingToken,
                IngestionStatus.RUNNING.name()) > 0;
    }

    /** 临时失败：进入 RETRY_WAIT，按有上限的退避等待下一次领取。 */
    public boolean retryLater(String jobId, long fencingToken, IngestionErrorCode code, int delaySeconds) {
        return jdbc.update("UPDATE exobrain_ingestion_job SET status = ?, error_code = ?, error_message = ?,"
                        + " next_attempt_at = NOW() + (? * INTERVAL '1 second'),"
                        + " lease_owner = NULL, lease_expires_at = NULL, updated_at = NOW()"
                        + " WHERE id = CAST(? AS uuid) AND fencing_token = ? AND status = ?",
                IngestionStatus.RETRY_WAIT.name(), code.name(), code.safeMessage(), Math.max(1, delaySeconds),
                jobId, fencingToken, IngestionStatus.RUNNING.name()) > 0;
    }

    /** 用完尝试次数的任务收尾为 FAILED（含崩溃后长期过期、无人再领取的 RUNNING）。 */
    public int failExhausted() {
        return jdbc.update("UPDATE exobrain_ingestion_job SET status = ?, error_code = ?, error_message = ?,"
                        + " lease_owner = NULL, lease_expires_at = NULL, finished_at = NOW(), updated_at = NOW()"
                        + " WHERE (((status IN (?, ?)) AND attempts >= max_attempts)"
                        + "    OR (status = ? AND lease_expires_at IS NOT NULL AND lease_expires_at < NOW()"
                        + "        AND attempts >= max_attempts))",
                IngestionStatus.FAILED.name(), IngestionErrorCode.ATTEMPTS_EXHAUSTED.name(),
                IngestionErrorCode.ATTEMPTS_EXHAUSTED.safeMessage(),
                IngestionStatus.QUEUED.name(), IngestionStatus.RETRY_WAIT.name(), IngestionStatus.RUNNING.name());
    }

    /**
     * 用户点击“重试”：只对最近一条终态任务生效，并且要求它当前不是活动状态。
     * 并发重复点击时只有一条 UPDATE 拿到行锁，另一次返回 0（不会生成第二个任务）。
     */
    public boolean requeue(String jobId) {
        return jdbc.update("UPDATE exobrain_ingestion_job SET status = ?, attempts = 0,"
                        + " next_attempt_at = NOW(), lease_owner = NULL, lease_expires_at = NULL,"
                        + " fencing_token = fencing_token + 1, chunks_done = 0, chunks_total = NULL,"
                        + " error_code = NULL, error_message = NULL, finished_at = NULL, updated_at = NOW()"
                        + " WHERE id = CAST(? AS uuid) AND status IN (?, ?)",
                IngestionStatus.QUEUED.name(), jobId, IngestionStatus.FAILED.name(),
                IngestionStatus.CANCELLED.name()) > 0;
    }

    /** 孤儿文件清理的判断依据：文档是否仍然存在、是否有任务引用它。 */
    public boolean documentExists(String kbId, String documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM document WHERE id = CAST(? AS uuid) AND kb_id = CAST(? AS uuid))",
                Boolean.class, documentId, kbId));
    }

    public boolean knowledgeBaseExists(String kbId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM knowledge_base WHERE id = CAST(? AS uuid))", Boolean.class, kbId));
    }

    public boolean hasJobForDocument(String documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM exobrain_ingestion_job WHERE document_id = CAST(? AS uuid))",
                Boolean.class, documentId));
    }

    public int countActive() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM exobrain_ingestion_job WHERE status IN (?, ?, ?)",
                Integer.class, IngestionStatus.QUEUED.name(), IngestionStatus.RUNNING.name(),
                IngestionStatus.RETRY_WAIT.name());
        return count == null ? 0 : count;
    }
}
