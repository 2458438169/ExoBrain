package com.kama.jchatmind.execution;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 运行记录的 JDBC 实现；表结构见 {@code deploy/migrations/20261017-05-runs.sql}。 */
@Repository
public class JdbcRunRepository implements RunRepository {

    private static final String COLUMNS = "id::text, schema_version, session_id::text, "
            + "triggering_message_id::text, agent_id::text, agent_name_at_start, agent_config_hash, "
            + "model_catalog_id, model_name_at_start, status, submitted_at, started_at, finished_at, "
            + "duration_ms, queue_duration_ms, input_tokens, output_tokens, total_tokens, error_code, "
            + "answer_message_id::text, trace_incomplete, heartbeat_at, origin";

    private static final RowMapper<RunSummary> RUN = JdbcRunRepository::mapRun;

    private static final RowMapper<RunEventRecord> EVENT = (rs, row) -> new RunEventRecord(
            rs.getString("id"),
            rs.getString("run_id"),
            rs.getInt("sequence"),
            RunEventType.valueOf(rs.getString("type")),
            RunEventStatus.valueOf(rs.getString("status")),
            instant(rs, "started_at"),
            rs.getLong("duration_ms"),
            rs.getString("tool_call_id"),
            rs.getString("tool_name"),
            rs.getString("retrieval_id"),
            (Integer) rs.getObject("retry_attempt"),
            rs.getString("error_code"),
            rs.getString("detail"));

    private final JdbcTemplate jdbc;

    public JdbcRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(RunSummary run) {
        jdbc.update("INSERT INTO exobrain_run (id, schema_version, session_id, triggering_message_id, "
                        + "agent_id, agent_name_at_start, agent_config_hash, model_catalog_id, model_name_at_start, "
                        + "status, origin, submitted_at, started_at, finished_at, duration_ms, queue_duration_ms, "
                        + "input_tokens, output_tokens, total_tokens, error_code, answer_message_id, "
                        + "trace_incomplete, heartbeat_at) VALUES (CAST(? AS uuid), ?, CAST(? AS uuid), "
                        + "CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "CAST(? AS uuid), ?, ?)",
                run.runId(), run.schemaVersion(), run.sessionId(), run.triggeringMessageId(), run.agentId(),
                run.agentNameAtStart(), run.agentConfigHash(), run.modelCatalogId(), run.modelNameAtStart(),
                run.status().name(), run.origin().name(), timestamp(run.submittedAt()), timestamp(run.startedAt()),
                timestamp(run.finishedAt()), run.durationMs(), run.queueDurationMs(), run.inputTokens(),
                run.outputTokens(), run.totalTokens(), run.errorCode(), run.answerMessageId(),
                run.traceIncomplete(), timestamp(run.heartbeatAt() == null ? Instant.now() : run.heartbeatAt()));
    }

    @Override
    public RunSummary findById(String runId) {
        if (!validId(runId)) {
            return null;
        }
        List<RunSummary> found = jdbc.query("SELECT " + COLUMNS + " FROM exobrain_run WHERE id = CAST(? AS uuid)",
                RUN, UUID.fromString(runId).toString());
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public List<RunSummary> findBySessionId(String sessionId, RunOrigin origin) {
        if (!validId(sessionId)) {
            return List.of();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM exobrain_run WHERE session_id = CAST(? AS uuid) "
                        + "AND origin = ? ORDER BY submitted_at ASC, id ASC",
                RUN, UUID.fromString(sessionId).toString(), origin.name());
    }

    @Override
    public void appendEvent(RunEventRecord event) {
        jdbc.update("INSERT INTO exobrain_run_event (id, run_id, sequence, type, status, started_at, "
                        + "duration_ms, tool_call_id, tool_name, retrieval_id, retry_attempt, error_code, detail) "
                        + "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                event.eventId(), event.runId(), event.sequence(), event.type().name(), event.status().name(),
                timestamp(event.startedAt()), event.durationMs(), event.toolCallId(), event.toolName(),
                event.retrievalId(), event.retryAttempt(), event.errorCode(), event.detail());
    }

    @Override
    public List<RunEventRecord> findEvents(String runId) {
        if (!validId(runId)) {
            return List.of();
        }
        return jdbc.query("SELECT id::text, run_id::text, sequence, type, status, started_at, duration_ms, "
                        + "tool_call_id, tool_name, retrieval_id, retry_attempt, error_code, detail "
                        + "FROM exobrain_run_event WHERE run_id = CAST(? AS uuid) ORDER BY sequence ASC",
                EVENT, UUID.fromString(runId).toString());
    }

    @Override
    public int complete(String runId, RunStatus target, Instant finishedAt, Long durationMs,
                        TokenUsage usage, String errorCode, boolean traceIncomplete) {
        if (!validId(runId) || target == null || !target.isTerminal()) {
            return 0;
        }
        TokenUsage tokens = usage == null ? TokenUsage.UNKNOWN : usage;
        return jdbc.update("UPDATE exobrain_run SET status = ?, finished_at = ?, duration_ms = ?, "
                        + "input_tokens = ?, output_tokens = ?, total_tokens = ?, error_code = ?, "
                        + "trace_incomplete = (trace_incomplete OR ?), heartbeat_at = NOW() "
                        + "WHERE id = CAST(? AS uuid) AND (status IN ('QUEUED', 'RUNNING') OR status = ?)",
                target.name(), timestamp(finishedAt), durationMs, tokens.inputTokens(), tokens.outputTokens(),
                tokens.totalTokens(), errorCode, traceIncomplete, UUID.fromString(runId).toString(),
                target.name());
    }

    @Override
    public int setAnswerMessageId(String runId, String answerMessageId) {
        if (!validId(runId) || !validId(answerMessageId)) {
            return 0;
        }
        return jdbc.update("UPDATE exobrain_run SET answer_message_id = CAST(? AS uuid) "
                        + "WHERE id = CAST(? AS uuid) AND answer_message_id IS NULL",
                UUID.fromString(answerMessageId).toString(), UUID.fromString(runId).toString());
    }

    @Override
    public int setAnswerMessageIdIfRunning(String runId, String answerMessageId) {
        if (!validId(runId) || !validId(answerMessageId)) {
            return 0;
        }
        return jdbc.update("UPDATE exobrain_run SET answer_message_id = CAST(? AS uuid) "
                        + "WHERE id = CAST(? AS uuid) AND status = 'RUNNING' AND answer_message_id IS NULL",
                UUID.fromString(answerMessageId).toString(), UUID.fromString(runId).toString());
    }

    @Override
    public int touch(String runId) {
        if (!validId(runId)) {
            return 0;
        }
        return jdbc.update("UPDATE exobrain_run SET heartbeat_at = NOW() WHERE id = CAST(? AS uuid)",
                UUID.fromString(runId).toString());
    }

    private static RunSummary mapRun(ResultSet rs, int row) throws SQLException {
        return new RunSummary(
                rs.getInt("schema_version"),
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("triggering_message_id"),
                rs.getString("agent_id"),
                rs.getString("agent_name_at_start"),
                rs.getString("agent_config_hash"),
                rs.getString("model_catalog_id"),
                rs.getString("model_name_at_start"),
                RunStatus.valueOf(rs.getString("status")),
                instant(rs, "submitted_at"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                (Long) rs.getObject("duration_ms"),
                (Long) rs.getObject("queue_duration_ms"),
                (Integer) rs.getObject("input_tokens"),
                (Integer) rs.getObject("output_tokens"),
                (Integer) rs.getObject("total_tokens"),
                rs.getString("error_code"),
                rs.getString("answer_message_id"),
                rs.getBoolean("trace_incomplete"),
                instant(rs, "heartbeat_at"),
                RunOrigin.valueOf(rs.getString("origin")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, java.time.ZoneOffset.UTC);
    }

    private static boolean validId(String id) {
        try {
            return id != null && UUID.fromString(id).toString().equalsIgnoreCase(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}