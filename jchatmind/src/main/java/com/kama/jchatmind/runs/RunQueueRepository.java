package com.kama.jchatmind.runs;

import com.kama.jchatmind.execution.RunOrigin;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 对话排队任务的读写（表 {@code exobrain_chat_run_job}，迁移 {@code deploy/migrations/20261017-07-durable-runs.sql}）。
 *
 * <p>并发约定（与 06 的导入队列相同的已验证模式，但表完全独立）：</p>
 * <ul>
 *   <li>{@link #claim} 是一条语句（{@code FOR UPDATE SKIP LOCKED} + {@code UPDATE ... RETURNING}）：
 *       不需要长事务，多实例启动时只有一个实例能拿到同一任务。</li>
 *   <li>只领取 {@code QUEUED}。已经 {@code RUNNING} 的任务<b>不会</b>被重新领取：对话有外部副作用
 *       （工具调用、已落库的答复），无法判断“执行到哪一步”，租约过期后由
 *       {@link RunTerminalWriter#interruptExpired()} 诚实标记 {@code INTERRUPTED}。</li>
 *   <li>续租、终态写回全部带 {@code fencing_token} 与 {@code status='RUNNING'} 条件，旧 worker 一个字都写不回。</li>
 * </ul>
 *
 * <p>这里也包含少量对 05 运行摘要（{@code exobrain_run}）的状态推进语句（提交建立 QUEUED、领取改 RUNNING），
 * 因为 05 的 {@code RunRepository} 只提供终态写入；终态仍通过 {@code RunRepository.complete} 写入，
 * 不改 05 的语义。</p>
 */
@Repository
public class RunQueueRepository {

    private static final String COLUMNS = "id::text, run_id::text, origin, session_id::text, "
            + "triggering_message_id::text, agent_id::text, actor_scope, actor_user_id::text, actor_auth_version, "
            + "actor_session_hash, demo_owner, idempotency_key, request_digest, user_input, history_snapshot, "
            + "status, attempts, max_attempts, cancel_requested, lease_owner, lease_expires_at, fencing_token, "
            + "error_code, created_at, started_at, finished_at";

    private static final RowMapper<RunQueueJob> ROW = RunQueueRepository::map;

    private final JdbcTemplate jdbc;

    public RunQueueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static RunQueueJob map(ResultSet rs, int row) throws SQLException {
        return new RunQueueJob(
                rs.getString("id"), rs.getString("run_id"), RunOrigin.valueOf(rs.getString("origin")),
                rs.getString("session_id"), rs.getString("triggering_message_id"), rs.getString("agent_id"),
                rs.getString("actor_scope"), rs.getString("actor_user_id"),
                (Integer) rs.getObject("actor_auth_version"), rs.getString("actor_session_hash"),
                rs.getString("demo_owner"), rs.getString("idempotency_key"), rs.getString("request_digest"),
                rs.getString("user_input"), rs.getString("history_snapshot"),
                RunQueueStatus.valueOf(rs.getString("status")), rs.getInt("attempts"), rs.getInt("max_attempts"),
                rs.getBoolean("cancel_requested"), rs.getString("lease_owner"), instant(rs, "lease_expires_at"),
                rs.getLong("fencing_token"), rs.getString("error_code"), instant(rs, "created_at"),
                instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public void insert(RunQueueJob job) {
        jdbc.update("INSERT INTO exobrain_chat_run_job (id, run_id, origin, session_id, triggering_message_id, "
                        + "agent_id, actor_scope, actor_user_id, actor_auth_version, actor_session_hash, demo_owner, "
                        + "idempotency_key, request_digest, user_input, history_snapshot, status, attempts, "
                        + "max_attempts, fencing_token, created_at, updated_at) "
                        + "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS uuid), CAST(? AS uuid), "
                        + "CAST(? AS uuid), ?, CAST(? AS uuid), ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, ?, NOW())",
                job.id(), job.runId(), job.origin().name(), job.sessionId(), job.triggeringMessageId(),
                job.agentId(), job.actorScope(), job.actorUserId(), job.actorAuthVersion(), job.actorSessionHash(),
                job.demoOwner(), job.idempotencyKey(), job.requestDigest(), job.userInput(), job.historySnapshot(),
                job.status().name(), job.maxAttempts(), Timestamp.from(job.createdAt()));
    }

    public Optional<RunQueueJob> findById(String jobId) {
        if (!validId(jobId)) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM exobrain_chat_run_job WHERE id = CAST(? AS uuid)",
                ROW, canonical(jobId)).stream().findFirst();
    }

    public Optional<RunQueueJob> findByRunId(String runId) {
        if (!validId(runId)) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM exobrain_chat_run_job WHERE run_id = CAST(? AS uuid)",
                ROW, canonical(runId)).stream().findFirst();
    }

    public Optional<RunQueueJob> findByIdempotencyKey(String actorScope, String sessionId, String key) {
        if (!validId(sessionId) || key == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM exobrain_chat_run_job WHERE actor_scope = ? "
                        + "AND session_id = CAST(? AS uuid) AND idempotency_key = ?",
                ROW, actorScope, canonical(sessionId), key).stream().findFirst();
    }

    public Optional<RunQueueJob> findActiveBySession(String sessionId) {
        if (!validId(sessionId)) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM exobrain_chat_run_job WHERE session_id = CAST(? AS uuid) "
                        + "AND status IN ('QUEUED', 'RUNNING') ORDER BY created_at LIMIT 1",
                ROW, canonical(sessionId)).stream().findFirst();
    }

    /**
     * 短事务领取：只取最早的 {@code QUEUED}。每次领取 {@code fencing_token + 1} 并写租约。
     * 没有候选时返回空，不抛异常。
     */
    public Optional<RunQueueJob> claim(String leaseOwner, int leaseSeconds) {
        return jdbc.query("WITH candidate AS ("
                        + " SELECT id FROM exobrain_chat_run_job WHERE status = 'QUEUED'"
                        + " ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1)"
                        + " UPDATE exobrain_chat_run_job job SET"
                        + "  status = 'RUNNING', attempts = job.attempts + 1, lease_owner = ?,"
                        + "  lease_expires_at = NOW() + (? * INTERVAL '1 second'),"
                        + "  fencing_token = job.fencing_token + 1,"
                        + "  started_at = COALESCE(job.started_at, NOW()), updated_at = NOW()"
                        + " FROM candidate WHERE job.id = candidate.id"
                        + " RETURNING " + prefixed("job."),
                ROW, leaseOwner, Math.max(1, leaseSeconds)).stream().findFirst();
    }

    /** 终态写入前的加锁读：必须在调用方事务内执行，行锁持有到事务结束。 */
    public Optional<RunQueueJob> lockById(String jobId) {
        if (!validId(jobId)) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM exobrain_chat_run_job WHERE id = CAST(? AS uuid) FOR UPDATE",
                ROW, canonical(jobId)).stream().findFirst();
    }

    /**
     * 续租 + 检查取消请求。返回 false 表示租约已丢失或收到取消：调用方必须立刻在检查点停止，
     * 不得再发起新的模型/工具调用。
     */
    public boolean renew(String jobId, long fencingToken, int leaseSeconds) {
        return jdbc.update("UPDATE exobrain_chat_run_job SET"
                        + " lease_expires_at = NOW() + (? * INTERVAL '1 second'), updated_at = NOW()"
                        + " WHERE id = CAST(? AS uuid) AND fencing_token = ? AND status = 'RUNNING'"
                        + " AND cancel_requested = FALSE AND lease_expires_at > NOW()",
                Math.max(1, leaseSeconds), canonical(jobId), fencingToken) > 0;
    }

    /** 心跳续租的三种结局：续上了 / 被别的锁挡住（下一拍再试）/ 租约真的没了（取消、接管或终态）。 */
    public enum RenewOutcome {
        RENEWED,
        BLOCKED,
        LOST
    }

    private static final String RENEW_SQL = "UPDATE exobrain_chat_run_job SET"
            + " lease_expires_at = NOW() + (? * INTERVAL '1 second'), updated_at = NOW()"
            + " WHERE id = CAST(? AS uuid) AND fencing_token = ? AND status = 'RUNNING'"
            + " AND cancel_requested = FALSE AND lease_expires_at > NOW()";

    /**
     * 心跳专用的续租：独立连接 + 独立事务 + {@code lock_timeout}。
     *
     * <p>普通 {@link #renew} 会一直等待行锁：如果同一个任务的行正被一条慢消息事务持有，这条 UPDATE 会挂住
     * 调用它的线程。心跳因此单独走这里——被锁挡住就如实返回 {@link RenewOutcome#BLOCKED}，让下一拍重试，
     * 而不是把运行判成“没有租约”。</p>
     */
    public RenewOutcome renewHeartbeat(String jobId, long fencingToken, int leaseSeconds, int lockTimeoutMs) {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            return renew(jobId, fencingToken, leaseSeconds) ? RenewOutcome.RENEWED : RenewOutcome.LOST;
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL lock_timeout = " + Math.max(100, lockTimeoutMs));
                }
                int updated;
                try (PreparedStatement statement = connection.prepareStatement(RENEW_SQL)) {
                    statement.setInt(1, Math.max(1, leaseSeconds));
                    statement.setString(2, canonical(jobId));
                    statement.setLong(3, fencingToken);
                    updated = statement.executeUpdate();
                }
                connection.commit();
                return updated > 0 ? RenewOutcome.RENEWED : RenewOutcome.LOST;
            } catch (SQLException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // 回滚失败不影响判定：这一拍心跳本来就不写任何东西
                }
                if (blockedByLock(failure)) {
                    return RenewOutcome.BLOCKED;
                }
                // 其它数据库错误也按“这一拍没续上”处理：下一拍重试，真正的租约丢失由状态变更体现。
                return RenewOutcome.BLOCKED;
            } finally {
                try {
                    connection.setAutoCommit(autoCommit);
                } catch (SQLException ignored) {
                    // 连接关闭时由驱动自行恢复
                }
            }
        } catch (SQLException unavailable) {
            return RenewOutcome.BLOCKED;
        }
    }

    private static boolean blockedByLock(SQLException failure) {
        String state = failure.getSQLState();
        if ("55P03".equals(state) || "57014".equals(state)) {
            return true;
        }
        String message = failure.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("lock timeout");
    }

    /** 运行中取消：只记录协作式停止请求；不会撤回已经发出的外部请求。 */
    public boolean requestCancel(String jobId) {
        return jdbc.update("UPDATE exobrain_chat_run_job SET cancel_requested = TRUE, updated_at = NOW()"
                        + " WHERE id = CAST(? AS uuid) AND status = 'RUNNING' AND cancel_requested = FALSE",
                canonical(jobId)) > 0;
    }

    /** 终态写回（调用方已持锁并校验过 fencing）；返回 false 表示状态已经变了。 */
    public boolean markTerminal(String jobId, long fencingToken, RunQueueStatus status, String errorCode) {
        if (!status.isTerminal()) {
            throw new IllegalArgumentException("markTerminal 只接受终态");
        }
        return jdbc.update("UPDATE exobrain_chat_run_job SET status = ?, error_code = ?,"
                        + " lease_owner = NULL, lease_expires_at = NULL, updated_at = NOW(), finished_at = NOW()"
                        + " WHERE id = CAST(? AS uuid) AND fencing_token = ? AND status = 'RUNNING'",
                status.name(), errorCode, canonical(jobId), fencingToken) > 0;
    }

    /** 排队期间的原子取消：只有 QUEUED 会被改写，返回被取消的任务。 */
    public Optional<RunQueueJob> cancelQueued(String jobId) {
        return jdbc.query("UPDATE exobrain_chat_run_job SET status = 'CANCELLED', error_code = 'CANCELLED_BEFORE_START',"
                        + " updated_at = NOW(), finished_at = NOW()"
                        + " WHERE id = CAST(? AS uuid) AND status = 'QUEUED'"
                        + " RETURNING " + COLUMNS,
                ROW, canonical(jobId)).stream().findFirst();
    }

    /** 租约过期的 RUNNING 任务（只在恢复流程里读取；不会把别的实例仍在续租的任务算进来）。 */
    public List<RunQueueJob> expiredRunning(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exobrain_chat_run_job"
                        + " WHERE status = 'RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at < NOW()"
                        + " ORDER BY lease_expires_at LIMIT ?",
                ROW, Math.max(1, limit));
    }

    public int countQueued() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM exobrain_chat_run_job WHERE status = 'QUEUED'",
                Integer.class);
        return count == null ? 0 : count;
    }

    public int countActiveForActor(String actorScope) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM exobrain_chat_run_job "
                        + "WHERE actor_scope = ? AND status IN ('QUEUED', 'RUNNING')",
                Integer.class, actorScope);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ 05 运行摘要的状态推进

    /** 领取成功后把运行摘要从 QUEUED 改成 RUNNING（05 的 complete 只管终态）。 */
    public boolean startRun(String runId, Instant startedAt, long queueDurationMs) {
        return jdbc.update("UPDATE exobrain_run SET status = 'RUNNING', started_at = ?, queue_duration_ms = ?,"
                        + " heartbeat_at = NOW() WHERE id = CAST(? AS uuid) AND status = 'QUEUED'",
                Timestamp.from(startedAt), Math.max(0L, queueDurationMs), canonical(runId)) > 0;
    }

    public String runStatus(String runId) {
        List<String> status = jdbc.query("SELECT status FROM exobrain_run WHERE id = CAST(? AS uuid)",
                (rs, row) -> rs.getString(1), canonical(runId));
        return status.isEmpty() ? null : status.get(0);
    }

    private static String prefixed(String prefix) {
        StringBuilder builder = new StringBuilder();
        for (String column : COLUMNS.split(", ")) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(prefix).append(column);
        }
        return builder.toString();
    }

    private static String canonical(String id) {
        return UUID.fromString(id).toString();
    }

    private static boolean validId(String id) {
        try {
            return id != null && UUID.fromString(id).toString().equalsIgnoreCase(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
