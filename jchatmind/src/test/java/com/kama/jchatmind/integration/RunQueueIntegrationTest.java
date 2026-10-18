package com.kama.jchatmind.integration;

import com.kama.jchatmind.execution.JdbcRunRepository;
import com.kama.jchatmind.execution.RunOrigin;
import com.kama.jchatmind.execution.RunRepository;
import com.kama.jchatmind.execution.RunStatus;
import com.kama.jchatmind.execution.RunSummary;
import com.kama.jchatmind.execution.TokenUsage;
import com.kama.jchatmind.runs.RunQueueJob;
import com.kama.jchatmind.runs.RunQueueRepository;
import com.kama.jchatmind.runs.RunQueueStatus;
import com.kama.jchatmind.runs.RunTerminalWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 任务 07 的队列语义在真实 PostgreSQL 上的验证：幂等键、会话内单活动运行、领取与 fencing、
 * 租约过期后的中断、取消与完成的竞争、以及重启后仍可领取 QUEUED。
 *
 * <p>与其它集成测试一样，它自己创建并删除一个一次性数据库，安装基线脚本与全部迁移，
 * 不触碰部署库。未配置数据库时整类如实跳过。</p>
 */
@Tag("postgres-it")
@DisplayName("Real PostgreSQL durable run queue (07)")
class RunQueueIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String DATABASE = "exobrain_rq_" + SUFFIX;

    private static String adminUrl;
    private static String databaseUser;
    private static String databasePassword;
    private static boolean requireDatabase;
    private static String skipReason;
    private static Connection admin;
    private static JdbcTemplate jdbc;
    private static RunQueueRepository jobs;
    private static RunTerminalWriter terminalWriter;

    @BeforeAll
    static void prepareDatabase() throws Exception {
        adminUrl = setting("exobrain.it.jdbcUrl", "EXOBRAIN_IT_JDBC_URL");
        databaseUser = setting("exobrain.it.username", "EXOBRAIN_IT_USERNAME");
        databasePassword = setting("exobrain.it.password", "EXOBRAIN_IT_PASSWORD");
        requireDatabase = Boolean.parseBoolean(setting("exobrain.it.requireDatabase", "EXOBRAIN_IT_REQUIRE_DATABASE"));
        if (adminUrl == null || adminUrl.isBlank()) {
            skipReason = "No PostgreSQL configured: real run-queue checks were NOT executed on this host";
            return;
        }
        try {
            admin = DriverManager.getConnection(adminUrl, databaseUser, databasePassword);
        } catch (SQLException unreachable) {
            if (requireDatabase) {
                throw new IllegalStateException("PostgreSQL is required but not reachable at " + adminUrl, unreachable);
            }
            skipReason = "PostgreSQL not reachable at " + adminUrl + ": real run-queue checks were NOT executed";
            return;
        }
        try (Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        String databaseUrl = adminUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + DATABASE + "$1");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword);
        jdbc = new JdbcTemplate(dataSource);
        List<Path> scripts = new ArrayList<>();
        scripts.add(repoFile("db/jchatmind.sql"));
        Path migrations = repoDirectory("deploy/migrations");
        try (var entries = Files.list(migrations)) {
            entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .forEach(scripts::add);
        }
        for (Path script : scripts) {
            install(script);
        }
        RunRepository runs = new JdbcRunRepository(jdbc);
        jobs = new RunQueueRepository(jdbc);
        terminalWriter = new RunTerminalWriter(jobs, runs);
        System.out.println("[run-queue-it] installed " + scripts.size() + " scripts into isolated database " + DATABASE);
    }

    @BeforeEach
    void cleanQueue() {
        // Tests run sequentially against one scratch database; give each test an empty queue so that
        // claim() can only ever return the job that this test created.
        jdbc.execute("DELETE FROM exobrain_chat_run_job");
        jdbc.execute("DELETE FROM exobrain_run");
        jdbc.execute("DELETE FROM chat_session");
    }

    @AfterAll
    static void dropDatabase() {
        if (admin == null) {
            return;
        }
        try (Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            System.out.println("[run-queue-it] dropped isolated database " + DATABASE);
        } catch (SQLException warning) {
            System.out.println("[run-queue-it] cleanup warning: " + warning.getMessage());
        } finally {
            try {
                admin.close();
            } catch (SQLException ignored) {
                // closing the admin connection must not fail the suite
            }
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("a queued job round-trips every server-side field")
    void roundTripKeepsEveryField() {
        String sessionId = newSession();
        String runId = newRun(sessionId);
        RunQueueJob job = demoJob(sessionId, runId, "demo-owner-a", "key-round-trip");

        jobs.insert(job);

        RunQueueJob stored = jobs.findById(job.id()).orElseThrow();
        assertEquals(job.id(), stored.id());
        assertEquals(runId, stored.runId());
        assertEquals(sessionId, stored.sessionId());
        assertEquals(RunQueueStatus.QUEUED, stored.status());
        assertEquals(0L, stored.fencingToken());
        assertEquals(0, stored.attempts());
        assertEquals(1, stored.maxAttempts());
        assertFalse(stored.cancelRequested());
        assertNull(stored.leaseOwner());
        assertNull(stored.leaseExpiresAt());
        assertEquals("demo-owner-a", stored.demoOwner());
        assertNull(stored.actorUserId());
        assertEquals("fixed question", stored.userInput());
        assertEquals("[]", stored.historySnapshot());
        assertNotNull(jobs.findByRunId(runId).orElse(null));
    }

    @Test
    @DisplayName("the idempotency key is unique per actor and session")
    void idempotencyKeyIsUniquePerActorAndSession() {
        String sessionId = newSession();
        String otherSession = newSession();
        jobs.insert(demoJob(sessionId, newRun(sessionId), "owner-1", "same-key"));

        assertThrows(DataAccessException.class, () ->
                        jobs.insert(demoJob(sessionId, newRun(sessionId), "owner-1", "same-key")),
                "the same actor, session and key must not create a second job");
        // The same key in another session, or for another actor, is a different submission intent.
        jobs.insert(demoJob(otherSession, newRun(otherSession), "owner-1", "same-key"));
        String thirdSession = newSession();
        jobs.insert(demoJob(thirdSession, newRun(thirdSession), "owner-2", "same-key"));

        assertEquals("same-key", jobs.findByIdempotencyKey("DEMO:owner-1", sessionId, "same-key")
                .orElseThrow().idempotencyKey());
        assertTrue(jobs.findByIdempotencyKey("DEMO:owner-1", sessionId, "unknown-key").isEmpty());
    }

    @Test
    @DisplayName("a session has at most one active job, and a terminal job frees the slot")
    void secondActiveJobForSameSessionIsRejected() {
        String sessionId = newSession();
        String runId = newRun(sessionId);
        jobs.insert(demoJob(sessionId, runId, "owner-x", "key-active-1"));

        assertThrows(DataAccessException.class, () ->
                        jobs.insert(demoJob(sessionId, newRun(sessionId), "owner-x", "key-active-2")),
                "the database, not an in-memory set, must enforce one active run per session");
        assertEquals("key-active-1", jobs.findActiveBySession(sessionId).orElseThrow().idempotencyKey());

        RunQueueJob claimed = jobs.claim("worker-a", 120).orElseThrow();
        assertEquals(runId, claimed.runId());
        assertTrue(terminalWriter.finish(claimed.id(), claimed.fencingToken(), claimed.runId(),
                RunStatus.SUCCEEDED, 42L, TokenUsage.UNKNOWN, null, false));

        assertTrue(jobs.findActiveBySession(sessionId).isEmpty(), "a finished run must free the session slot");
        jobs.insert(demoJob(sessionId, newRun(sessionId), "owner-x", "key-active-3"));
    }

    @Test
    @DisplayName("claiming marks RUNNING and bumps fencing and attempts")
    void claimMarksRunningAndBumpsFencingAndAttempts() {
        String sessionId = newSession();
        String runId = newRun(sessionId);
        RunQueueJob queued = demoJob(sessionId, runId, "owner-c", "key-claim");
        jobs.insert(queued);

        RunQueueJob claimed = jobs.claim("worker-1", 120).orElseThrow();

        assertEquals(queued.id(), claimed.id());
        assertEquals(RunQueueStatus.RUNNING, claimed.status());
        assertEquals(1L, claimed.fencingToken());
        assertEquals(1, claimed.attempts());
        assertEquals("worker-1", claimed.leaseOwner());
        assertNotNull(claimed.leaseExpiresAt());
        assertTrue(claimed.leaseExpiresAt().isAfter(Instant.now()), "the lease must expire in the future");
        // Claiming the job does not start the run record: the worker calls startRun when it begins,
        // so a claimed-but-not-yet-started job still shows QUEUED on the run summary.
        assertEquals("QUEUED", jdbc.queryForObject(
                "SELECT status FROM exobrain_run WHERE id = CAST(? AS uuid)", String.class, runId));
        assertTrue(jobs.startRun(runId, Instant.now(), 7L), "the worker marks the run RUNNING when it starts");
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT status FROM exobrain_run WHERE id = CAST(? AS uuid)", String.class, runId));
    }

    @Test
    @DisplayName("claiming only ever picks QUEUED jobs")
    void claimIgnoresRunningAndTerminalJobs() {
        String runningSession = newSession();
        jobs.insert(demoJob(runningSession, newRun(runningSession), "owner-r", "key-run"));
        RunQueueJob running = jobs.claim("worker-1", 120).orElseThrow();

        String doneSession = newSession();
        jobs.insert(demoJob(doneSession, newRun(doneSession), "owner-d", "key-done"));
        RunQueueJob done = jobs.findByIdempotencyKey("DEMO:owner-d", doneSession, "key-done").orElseThrow();
        assertTrue(jobs.cancelQueued(done.id()).isPresent());

        assertTrue(jobs.claim("worker-2", 120).isEmpty(),
                "a RUNNING or terminal job must never be claimed a second time");
        assertEquals(1L, jobs.findById(running.id()).orElseThrow().fencingToken());
    }

    @Test
    @DisplayName("renew only works for the current fencing token")
    void renewRequiresCurrentFencingToken() {
        String sessionId = newSession();
        jobs.insert(demoJob(sessionId, newRun(sessionId), "owner-n", "key-renew"));
        RunQueueJob claimed = jobs.claim("worker-1", 120).orElseThrow();

        assertTrue(jobs.renew(claimed.id(), claimed.fencingToken(), 120));
        assertFalse(jobs.renew(claimed.id(), claimed.fencingToken() + 5, 120),
                "a worker with a stale fencing token must not extend the lease");
    }

    @Test
    @DisplayName("an expired lease is interrupted once and a late worker cannot finish it")
    void staleWorkerCannotWriteAfterInterrupt() {
        String sessionId = newSession();
        String runId = newRun(sessionId);
        jobs.insert(demoJob(sessionId, runId, "owner-i", "key-interrupt"));
        RunQueueJob claimed = jobs.claim("worker-1", 120).orElseThrow();
        expireLease(claimed.id());

        assertEquals(1, terminalWriter.interruptExpired(10));

        RunQueueJob interrupted = jobs.findById(claimed.id()).orElseThrow();
        assertEquals(RunQueueStatus.INTERRUPTED, interrupted.status());
        assertEquals(RunTerminalWriter.INTERRUPTED_BY_RESTART, interrupted.errorCode());
        assertNotNull(interrupted.finishedAt());
        RunSummary run = new JdbcRunRepository(jdbc).findById(runId);
        assertEquals(RunStatus.INTERRUPTED, run.status());
        assertEquals(RunTerminalWriter.INTERRUPTED_BY_RESTART, run.errorCode());

        assertFalse(terminalWriter.finish(claimed.id(), claimed.fencingToken(), runId,
                        RunStatus.SUCCEEDED, 10L, TokenUsage.UNKNOWN, null, false),
                "the worker that lost its lease must not append a second terminal state");
        assertEquals(RunQueueStatus.INTERRUPTED, jobs.findById(claimed.id()).orElseThrow().status());
        assertEquals(RunStatus.INTERRUPTED, new JdbcRunRepository(jdbc).findById(runId).status());
        assertEquals(0, terminalWriter.interruptExpired(10), "interrupting is not repeated for the same job");
    }

    @Test
    @DisplayName("interrupting skips jobs another instance is still renewing")
    void interruptExpiredSkipsLiveLeaseAndTerminalJobs() {
        String expiredSession = newSession();
        jobs.insert(demoJob(expiredSession, newRun(expiredSession), "owner-e", "key-exp"));
        RunQueueJob expired = jobs.claim("worker-1", 120).orElseThrow();
        expireLease(expired.id());

        String liveSession = newSession();
        jobs.insert(demoJob(liveSession, newRun(liveSession), "owner-l", "key-live"));
        RunQueueJob live = jobs.claim("worker-2", 600).orElseThrow();

        assertEquals(1, terminalWriter.interruptExpired(10), "only the expired lease is interrupted");
        assertEquals(RunQueueStatus.INTERRUPTED, jobs.findById(expired.id()).orElseThrow().status());
        assertEquals(RunQueueStatus.RUNNING, jobs.findById(live.id()).orElseThrow().status(),
                "a live lease from another instance must not be interrupted");
    }

    @Test
    @DisplayName("cancelling a queued job is atomic and idempotent")
    void cancelWhileQueuedIsAtomicAndIdempotent() {
        String sessionId = newSession();
        String runId = newRun(sessionId);
        jobs.insert(demoJob(sessionId, runId, "owner-q", "key-cancel-queued"));

        assertEquals(RunTerminalWriter.CancelOutcome.CANCELLED_WHILE_QUEUED, terminalWriter.cancel(runId));

        RunQueueJob cancelled = jobs.findByRunId(runId).orElseThrow();
        assertEquals(RunQueueStatus.CANCELLED, cancelled.status());
        assertEquals("CANCELLED_BEFORE_START", cancelled.errorCode());
        assertNotNull(cancelled.finishedAt());
        assertEquals(RunStatus.CANCELLED, new JdbcRunRepository(jdbc).findById(runId).status());

        assertEquals(RunTerminalWriter.CancelOutcome.ALREADY_TERMINAL, terminalWriter.cancel(runId));
        assertTrue(jobs.claim("worker-late", 120).isEmpty(), "a cancelled job must not be claimed");
    }

    @Test
    @DisplayName("cancelling a running job only requests a cooperative stop")
    void cancelWhileRunningOnlyRequestsCooperativeStop() {
        String sessionId = newSession();
        String runId = newRun(sessionId);
        jobs.insert(demoJob(sessionId, runId, "owner-run", "key-cancel-running"));
        RunQueueJob claimed = jobs.claim("worker-1", 120).orElseThrow();

        assertEquals(RunTerminalWriter.CancelOutcome.CANCEL_REQUESTED, terminalWriter.cancel(runId));

        RunQueueJob requested = jobs.findById(claimed.id()).orElseThrow();
        assertTrue(requested.cancelRequested());
        assertEquals(RunQueueStatus.RUNNING, requested.status(),
                "an in-flight external call cannot be rolled back; the row stays RUNNING until the checkpoint");
        assertNull(requested.finishedAt());
        assertFalse(jobs.renew(claimed.id(), claimed.fencingToken(), 120),
                "a cancel request must stop the worker at the next checkpoint");
    }

    @Test
    @DisplayName("cancel and completion never produce two terminal states")
    void cancelThenCompleteLeavesSingleTerminalState() {
        String sessionId = newSession();
        String runId = newRun(sessionId);
        jobs.insert(demoJob(sessionId, runId, "owner-race", "key-race"));
        RunQueueJob claimed = jobs.claim("worker-1", 120).orElseThrow();

        assertEquals(RunTerminalWriter.CancelOutcome.CANCEL_REQUESTED, terminalWriter.cancel(runId));
        assertTrue(terminalWriter.finish(claimed.id(), claimed.fencingToken(), runId,
                RunStatus.CANCELLED, 5L, TokenUsage.UNKNOWN, "CANCELLED_BY_USER", false));
        assertEquals(RunQueueStatus.CANCELLED, jobs.findById(claimed.id()).orElseThrow().status());

        assertFalse(terminalWriter.finish(claimed.id(), claimed.fencingToken(), runId,
                RunStatus.SUCCEEDED, 9L, TokenUsage.UNKNOWN, null, false));
        assertEquals(RunQueueStatus.CANCELLED, jobs.findById(claimed.id()).orElseThrow().status());
        assertEquals(RunStatus.CANCELLED, new JdbcRunRepository(jdbc).findById(runId).status());
    }

    @Test
    @DisplayName("queue depth and per-actor activity are countable for the configured caps")
    void queueDepthAndActorCapCounts() {
        String owner = "owner-count-" + UUID.randomUUID();
        String firstSession = newSession();
        String secondSession = newSession();
        jobs.insert(demoJob(firstSession, newRun(firstSession), owner, "key-c1"));
        jobs.insert(demoJob(secondSession, newRun(secondSession), owner, "key-c2"));

        assertEquals(2, jobs.countActiveForActor("DEMO:" + owner));
        assertTrue(jobs.countQueued() >= 2);
    }

    @Test
    @DisplayName("a restart picks QUEUED jobs back up and never replays finished ones")
    void queuedJobSurvivesRestartAndIsClaimable() {
        // Insert and complete one job first, so the later claim can only pick the still-queued one.
        String finishedSession = newSession();
        String finishedRun = newRun(finishedSession);
        jobs.insert(demoJob(finishedSession, finishedRun, "owner-restart", "key-restart-done"));
        RunQueueJob finished = jobs.claim("worker-before-restart", 120).orElseThrow();
        assertEquals(finishedRun, finished.runId());
        assertTrue(terminalWriter.finish(finished.id(), finished.fencingToken(), finishedRun,
                RunStatus.SUCCEEDED, 3L, TokenUsage.UNKNOWN, null, false));

        String queuedSession = newSession();
        String queuedRun = newRun(queuedSession);
        jobs.insert(demoJob(queuedSession, queuedRun, "owner-restart", "key-restart-queued"));

        // A new process would build its own repository instance; the database is the source of truth.
        RunQueueRepository afterRestart = new RunQueueRepository(jdbc);
        RunQueueJob reclaimed = afterRestart.claim("worker-after-restart", 120).orElseThrow();

        assertEquals(queuedRun, reclaimed.runId());
        assertEquals(RunQueueStatus.RUNNING, reclaimed.status());
        assertEquals(1L, reclaimed.fencingToken(), "the first claim takes over with fencing 1");
        assertTrue(afterRestart.claim("worker-after-restart-2", 120).isEmpty(),
                "the already finished run must not be replayed");
    }

    // ------------------------------------------------------------------ fixtures

    private static String newSession() {
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_session (id, title) VALUES (CAST(? AS uuid), ?)", sessionId, "run-queue-it");
        return sessionId;
    }

    private static String newRun(String sessionId) {
        String runId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO exobrain_run (id, session_id, status, origin, submitted_at) "
                        + "VALUES (CAST(? AS uuid), CAST(? AS uuid), 'QUEUED', 'DEMO', NOW())",
                runId, sessionId);
        return runId;
    }

    private static RunQueueJob demoJob(String sessionId, String runId, String owner, String key) {
        return new RunQueueJob(UUID.randomUUID().toString(), runId, RunOrigin.DEMO, sessionId, null, null,
                "DEMO:" + owner, null, null, null, owner, key, "digest-" + key, "fixed question", "[]",
                RunQueueStatus.QUEUED, 0, 1, false, null, null, 0L, null, Instant.now(), null, null);
    }

    private static void expireLease(String jobId) {
        int updated = jdbc.update("UPDATE exobrain_chat_run_job SET lease_expires_at = NOW() - INTERVAL '1 hour' "
                + "WHERE id = CAST(? AS uuid)", jobId);
        assertEquals(1, updated);
    }

    // ------------------------------------------------------------------ infrastructure

    private static void install(Path script) {
        String sql;
        try {
            sql = Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("cannot read " + script, unreadable);
        }
        for (String statementText : PostgresBaselineIntegrationTest.splitStatements(sql)) {
            try {
                jdbc.execute(statementText);
            } catch (DataAccessException failure) {
                throw new IllegalStateException("failed statement in " + script + ": "
                        + statementText.replaceAll("\\s+", " "), failure);
            }
        }
    }

    private static Path repoFile(String relative) {
        for (Path candidate : List.of(Path.of("..", relative), Path.of(relative), Path.of("..", "..", relative))) {
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("cannot find " + relative);
    }

    private static Path repoDirectory(String relative) {
        for (Path candidate : List.of(Path.of("..", relative), Path.of(relative), Path.of("..", "..", relative))) {
            if (Files.isDirectory(candidate)) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("cannot find directory " + relative);
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property);
        return value == null || value.isBlank() ? System.getenv(environment) : value;
    }
}
