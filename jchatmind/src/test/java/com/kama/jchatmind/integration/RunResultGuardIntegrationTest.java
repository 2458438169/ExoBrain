package com.kama.jchatmind.integration;

import com.kama.jchatmind.execution.JdbcRunRepository;
import com.kama.jchatmind.execution.RunOrigin;
import com.kama.jchatmind.execution.RunRepository;
import com.kama.jchatmind.execution.RunStatus;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.runs.RunLease;
import com.kama.jchatmind.runs.RunLeaseLostException;
import com.kama.jchatmind.runs.RunQueueJob;
import com.kama.jchatmind.runs.RunQueueRepository;
import com.kama.jchatmind.runs.RunQueueSettings;
import com.kama.jchatmind.runs.RunQueueStatus;
import com.kama.jchatmind.runs.RunResultWriter;
import com.kama.jchatmind.runs.RunSubmissionException;
import com.kama.jchatmind.runs.RunCancelledException;
import com.kama.jchatmind.runs.RunSubmissionService;
import com.kama.jchatmind.runs.RunTerminalWriter;
import com.kama.jchatmind.service.ChatMessageFacadeService;
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
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验收缺陷 F01 / F03 在真实 PostgreSQL 上的回归。
 *
 * <p>F01：运行被标记 {@code INTERRUPTED} 之后，普通消息 INSERT 仍能成功，于是数据库会同时声称
 * “运行已中断”和“之后写入了最终答案”。这里让消息写入走真实的 {@link RunResultWriter}（会锁任务行、
 * 校验 fencing 与 RUNNING 状态），断言终态之后零迟到消息、零迟到答案关联。</p>
 *
 * <p>F03：{@code replayAfterConflict} 之前把参数顺序写反了
 * （{@code (actorScope, key, sessionId)} 而不是 {@code (actorScope, sessionId, key)}），
 * 同键并发重试会误报 409。这里直接调用真实方法核对返回的是原来那一次运行。</p>
 *
 * <p>与其它集成测试一样自建一次性数据库，未配置数据库时整类跳过。</p>
 */
@Tag("postgres-it")
@DisplayName("Late answers and idempotent replay guards (F01/F03)")
class RunResultGuardIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String DATABASE = "exobrain_guard_" + SUFFIX;

    private static String adminUrl;
    private static String databaseUser;
    private static String databasePassword;
    private static boolean requireDatabase;
    private static String skipReason;
    private static Connection admin;
    private static JdbcTemplate jdbc;
    private static RunQueueRepository jobs;
    private static RunRepository runs;
    private static RunTerminalWriter terminalWriter;
    private static RunResultWriter resultWriter;
    private static ChatMessageFacadeService chatMessages;

    @BeforeAll
    static void prepareDatabase() throws Exception {
        adminUrl = setting("exobrain.it.jdbcUrl", "EXOBRAIN_IT_JDBC_URL");
        databaseUser = setting("exobrain.it.username", "EXOBRAIN_IT_USERNAME");
        databasePassword = setting("exobrain.it.password", "EXOBRAIN_IT_PASSWORD");
        requireDatabase = Boolean.parseBoolean(setting("exobrain.it.requireDatabase", "EXOBRAIN_IT_REQUIRE_DATABASE"));
        if (adminUrl == null || adminUrl.isBlank()) {
            skipReason = "No PostgreSQL configured: late-answer guards were NOT executed on this host";
            return;
        }
        try {
            admin = DriverManager.getConnection(adminUrl, databaseUser, databasePassword);
        } catch (SQLException unreachable) {
            if (requireDatabase) {
                throw new IllegalStateException("PostgreSQL is required but not reachable at " + adminUrl, unreachable);
            }
            skipReason = "PostgreSQL not reachable at " + adminUrl + ": late-answer guards were NOT executed";
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
        runs = new JdbcRunRepository(jdbc);
        jobs = new RunQueueRepository(jdbc);
        terminalWriter = new RunTerminalWriter(jobs, runs);
        chatMessages = mockChatMessageFacade();
        resultWriter = new RunResultWriter(jobs, runs, chatMessages);
        System.out.println("[run-guard-it] installed " + scripts.size() + " scripts into isolated database " + DATABASE);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM exobrain_chat_run_job");
        jdbc.execute("DELETE FROM exobrain_run");
        jdbc.execute("DELETE FROM chat_message");
        jdbc.execute("DELETE FROM chat_session");
    }

    @AfterAll
    static void dropDatabase() {
        if (admin == null) {
            return;
        }
        try (Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            System.out.println("[run-guard-it] dropped isolated database " + DATABASE);
        } catch (SQLException warning) {
            System.out.println("[run-guard-it] cleanup warning: " + warning.getMessage());
        } finally {
            try {
                admin.close();
            } catch (SQLException ignored) {
                // closing the admin connection must not fail the suite
            }
        }
    }

    // ------------------------------------------------------------------ F01

    @Test
    @DisplayName("an answer written after INTERRUPTED is rejected and leaves no message")
    void lateAnswerIsRejectedAfterInterruption() {
        assumeDatabase();
        String sessionId = newSession();
        String runId = newRun(sessionId);
        RunQueueJob job = demoJob(sessionId, runId, "owner-a", "key-late");
        jobs.insert(job);
        RunQueueJob claimed = claimAndStart("worker-a", runId);

        expireLease(claimed.id());
        assertEquals(1, terminalWriter.interruptExpired(5));
        assertEquals("INTERRUPTED", runStatus(runId));

        // 真实写入路径：锁任务行 → 校验 fencing/状态 → 才允许 INSERT。
        assertThrows(RunLeaseLostException.class, () -> resultWriter.persistMessage(
                claimed.id(), claimed.fencingToken(), runId, answer(sessionId, "迟到的答复"), true));

        assertEquals(0, count("SELECT count(*) FROM chat_message WHERE session_id = CAST(? AS uuid)", sessionId));
        assertNull(answerMessageId(runId));
        assertEquals("INTERRUPTED", runStatus(runId));
    }

    @Test
    @DisplayName("a running answer is written and linked in one transaction")
    void runningAnswerIsPersistedAndLinkedAtomically() {
        assumeDatabase();
        String sessionId = newSession();
        String runId = newRun(sessionId);
        jobs.insert(demoJob(sessionId, runId, "owner-b", "key-live"));
        RunQueueJob claimed = claimAndStart("worker-b", runId);

        String messageId = resultWriter.persistMessage(
                claimed.id(), claimed.fencingToken(), runId, answer(sessionId, "正常答复"), true);

        assertNotNull(messageId);
        assertEquals(1, count("SELECT count(*) FROM chat_message WHERE session_id = CAST(? AS uuid)", sessionId));
        assertEquals(messageId, answerMessageId(runId));
        assertEquals("RUNNING", runStatus(runId));

        // 之后运行被中断：已经落库的答复保留，但不会再接受第二条答案。
        expireLease(claimed.id());
        assertEquals(1, terminalWriter.interruptExpired(5));
        assertThrows(RunLeaseLostException.class, () -> resultWriter.persistMessage(
                claimed.id(), claimed.fencingToken(), runId, answer(sessionId, "第二条迟到的答复"), true));
        assertEquals(1, count("SELECT count(*) FROM chat_message WHERE session_id = CAST(? AS uuid)", sessionId));
        assertEquals(messageId, answerMessageId(runId));
    }

    @Test
    @DisplayName("a taken-over lease cannot write messages either")
    void leaseTakenOverRejectsLaterMessages() {
        assumeDatabase();
        String sessionId = newSession();
        String runId = newRun(sessionId);
        jobs.insert(demoJob(sessionId, runId, "owner-c", "key-takeover"));
        RunQueueJob first = claimAndStart("worker-c", runId);

        // 模拟租约被接管：fencing_token 前进（真实接管路径同样是 fencing+1），旧 worker 的 token 立刻失效。
        int bumped = jdbc.update("UPDATE exobrain_chat_run_job SET fencing_token = fencing_token + 1, "
                + "lease_owner = 'worker-d' WHERE id = CAST(? AS uuid)", first.id());
        assertEquals(1, bumped);

        assertThrows(RunLeaseLostException.class, () -> resultWriter.persistMessage(
                first.id(), first.fencingToken(), runId, answer(sessionId, "旧 worker 的迟到答复"), true));
        assertEquals(0, count("SELECT count(*) FROM chat_message WHERE session_id = CAST(? AS uuid)", sessionId));
    }

    @Test
    @DisplayName("the lease heartbeat keeps a live run from being interrupted by the expiry sweep")
    void heartbeatKeepsLeaseAlive() throws Exception {
        assumeDatabase();
        String sessionId = newSession();
        String runId = newRun(sessionId);
        jobs.insert(demoJob(sessionId, runId, "owner-e", "key-heartbeat"));
        RunQueueJob claimed = claimAndStart("worker-e", runId);

        RunLease lease = new RunLease(claimed, jobs, terminalWriter, runs, 1);
        lease.startHeartbeat();
        try {
            Thread.sleep(2_500L);
            // 心跳在 1 秒租约下续了至少一次，过期扫描不能再把它标记成中断。
            assertEquals(0, terminalWriter.interruptExpired(5));
            assertEquals("RUNNING", runStatus(runId));
        } finally {
            lease.close();
        }

        expireLease(claimed.id());
        assertEquals(1, terminalWriter.interruptExpired(5));
        assertEquals("INTERRUPTED", runStatus(runId));
    }

    // ------------------------------------------------------------------ R2-03

    @Test
    @DisplayName("a cancellation that outlives the lease is recorded as CANCELLED, not as a restart")
    void cancellationOutlivingTheLeaseIsNotReportedAsRestart() throws Exception {
        assumeDatabase();
        String sessionId = newSession();
        String runId = newRun(sessionId);
        jobs.insert(demoJob(sessionId, runId, "owner-g", "key-cancel"));
        RunQueueJob claimed = claimAndStart("worker-g", runId);

        RunLease lease = new RunLease(claimed, jobs, terminalWriter, runs, 1);
        lease.startHeartbeat();
        try {
            assertTrue(jobs.requestCancel(claimed.id()));
            // 跨过至少一个心跳周期：心跳必须把它识别为“用户取消”，不能当成租约丢失。
            Thread.sleep(1_800L);
            assertTrue(lease.isCancelled());
            assertThrows(RunCancelledException.class, lease::checkpoint);
        } finally {
            lease.close();
        }

        // 即便租约随后过期，过期扫描也必须写 CANCELLED，而不是 INTERRUPTED_BY_RESTART。
        expireLease(claimed.id());
        assertEquals(1, terminalWriter.interruptExpired(5));
        assertEquals("CANCELLED", runStatus(runId));
        assertEquals("CANCELLED_BY_USER", runErrorCode(runId));
    }

    // ------------------------------------------------------------------ R3-01

    @Test
    @DisplayName("a blocked checkpoint stops the run when the lease expires during the lock wait")
    void blockedCheckpointStopsAfterTheLeaseExpires() throws Exception {
        assumeDatabase();
        String sessionId = newSession();
        String runId = newRun(sessionId);
        jobs.insert(demoJob(sessionId, runId, "owner-r3a", "key-blocked-expired"));
        RunQueueJob claimed = claimAndStart("worker-r3a", runId);
        // 让租约在锁等待期间自然到期（不是提前过期，也不是靠改写 fencing / 取消请求）。
        assertEquals(1, jdbc.update("UPDATE exobrain_chat_run_job "
                + "SET lease_expires_at = NOW() + INTERVAL '1 second' WHERE id = CAST(? AS uuid)", claimed.id()));

        // 与执行器相同的租约句柄：3 秒租约 → 单次 lock_timeout 1 秒、确认预算 1 秒。
        RunLease lease = new RunLease(claimed, jobs, terminalWriter, runs, 3);
        CountDownLatch locked = new CountDownLatch(1);
        Thread holder = holdRowLock(claimed.id(), locked, 4_000L);
        try {
            assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            long start = System.nanoTime();
            // 修复前这里会直接 return 放行，让重试循环继续发出新的模型请求。
            assertThrows(RunLeaseLostException.class, lease::checkpoint);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(elapsedMs >= 1_000L, "checkpoint returned before confirming the lease: " + elapsedMs + "ms");
            assertTrue(elapsedMs < 4_000L, "checkpoint waited beyond its budget: " + elapsedMs + "ms");
        } finally {
            lease.close();
            holder.join(15_000L);
        }

        // 检查点停止后没有任何结果被写出：任务行留给过期扫描处理，答复一条都没有。
        assertEquals(0, count("SELECT count(*) FROM chat_message WHERE session_id = CAST(? AS uuid)", sessionId));
        assertNull(answerMessageId(runId));
    }

    @Test
    @DisplayName("a blocked checkpoint continues only when the lease is still confirmed valid")
    void blockedCheckpointContinuesOnlyWithAValidLease() throws Exception {
        assumeDatabase();
        String sessionId = newSession();
        String runId = newRun(sessionId);
        jobs.insert(demoJob(sessionId, runId, "owner-r3b", "key-blocked-valid"));
        // 领取时的 10 秒租约仍然有效；下面的句柄只把 lock_timeout / 确认预算压到 1 秒。
        RunQueueJob claimed = claimAndStart("worker-r3b", runId);

        RunLease lease = new RunLease(claimed, jobs, terminalWriter, runs, 3);
        CountDownLatch locked = new CountDownLatch(1);
        Thread holder = holdRowLock(claimed.id(), locked, 4_000L);
        try {
            assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            lease.checkpoint();
            // 放行的前提是被无锁读确认过：租约还在有效期内，且没有任何取消请求。
            assertTrue(leaseExpiry(claimed.id()).isAfter(Instant.now()));
            assertFalse(lease.isCancelled());
        } finally {
            lease.close();
            holder.join(15_000L);
        }
    }
    @Test
    @DisplayName("a blocked checkpoint that cannot confirm the lease stops the run")
    void blockedCheckpointStopsWhenTheLeaseCannotBeConfirmed() throws Exception {
        assumeDatabase();
        String sessionId = newSession();
        String runId = newRun(sessionId);
        jobs.insert(demoJob(sessionId, runId, "owner-r3c", "key-blocked-unconfirmed"));
        RunQueueJob claimed = claimAndStart("worker-r3c", runId);
        // 第一次续租时租约还有效（WHERE 条件成立 → 真的去抢行锁并被 lock_timeout 挡住），
        // 第二次尝试时仍然有效（还会被挡住），等预算耗尽做无锁读时它已经过期：这一拍必须停止。
        assertEquals(1, jdbc.update("UPDATE exobrain_chat_run_job "
                + "SET lease_expires_at = NOW() + INTERVAL '2500 milliseconds' WHERE id = CAST(? AS uuid)",
                claimed.id()));

        RunLease lease = new RunLease(claimed, jobs, terminalWriter, runs, 3);
        CountDownLatch locked = new CountDownLatch(1);
        // 第一次续租时租约还有效（WHERE 条件成立 → 真的去抢行锁并被 lock_timeout 挡住），
        // 等到预算耗尽再做无锁读时它已经过期：这一拍必须停止。
        Thread holder = holdRowLock(claimed.id(), locked, 5_000L);
        try {
            assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(RunLeaseLostException.class, lease::checkpoint);
            // 既没有续上租约（过期时间没被延长），也没有偷偷写终态：任务行留给过期扫描。
            assertTrue(leaseExpiry(claimed.id()).isBefore(Instant.now()));
        } finally {
            lease.close();
            holder.join(15_000L);
        }
        assertEquals(0, count("SELECT count(*) FROM chat_message WHERE session_id = CAST(? AS uuid)", sessionId));
        assertNull(answerMessageId(runId));
    }

    // ------------------------------------------------------------------ R2-04

    @Test
    @DisplayName("one run's row lock does not starve another run's heartbeat")
    void heartbeatIsIsolatedFromAnotherRunsRowLock() throws Exception {
        assumeDatabase();
        String sessionA = newSession();
        String runA = newRun(sessionA);
        jobs.insert(demoJob(sessionA, runA, "owner-h", "key-lock-a"));
        RunQueueJob claimedA = claimAndStart("worker-h1", runA);

        String sessionB = newSession();
        String runB = newRun(sessionB);
        jobs.insert(demoJob(sessionB, runB, "owner-i", "key-lock-b"));

        RunQueueJob claimedB = claimAndStart("worker-h2", runB);

        RunLease leaseA = new RunLease(claimedA, jobs, terminalWriter, runs, 2);
        RunLease leaseB = new RunLease(claimedB, jobs, terminalWriter, runs, 2);
        leaseA.startHeartbeat();
        leaseB.startHeartbeat();
        CountDownLatch locked = new CountDownLatch(1);
        Thread holder = holdRowLock(claimedA.id(), locked, 6_000L);
        try {
            assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            // A 的任务行被一条慢事务持有：A 自己的续租会被 lock_timeout 挡住，但不能拖累 B。
            Thread.sleep(4_500L);
            assertFalse(leaseB.isCancelled());
            assertEquals("RUNNING", runStatus(runB));
            assertTrue(leaseExpiry(claimedB.id()).isAfter(Instant.now()));
            // B 仍然可以正常收尾（说明它的租约与 fencing 都还有效）。
            assertTrue(leaseB.succeeded(null));
            assertEquals("SUCCEEDED", runStatus(runB));
        } finally {
            leaseA.close();
            leaseB.close();
            holder.join(10_000L);
        }
    }

    /** 在独立连接上用 {@code SELECT … FOR UPDATE} 长时间持有某一任务的行锁。 */
    private static Thread holdRowLock(String jobId, CountDownLatch locked, long holdMillis) {
        Thread holder = new Thread(() -> {
            try (java.sql.Connection connection = jdbc.getDataSource().getConnection()) {
                connection.setAutoCommit(false);
                try (java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM exobrain_chat_run_job WHERE id = CAST(? AS uuid) FOR UPDATE")) {
                    statement.setString(1, jobId);
                    statement.executeQuery();
                }
                locked.countDown();
                Thread.sleep(holdMillis);
                connection.rollback();
            } catch (Exception ignored) {
                locked.countDown();
            }
        }, "row-lock-holder");
        holder.setDaemon(true);
        holder.start();
        return holder;
    }


    private static Instant leaseExpiry(String jobId) {
        java.sql.Timestamp value = jdbc.queryForObject(
                "SELECT lease_expires_at FROM exobrain_chat_run_job WHERE id = CAST(? AS uuid)",
                java.sql.Timestamp.class, jobId);
        return value == null ? Instant.EPOCH : value.toInstant();
    }

    private static String runErrorCode(String runId) {
        return jdbc.queryForObject("SELECT error_code FROM exobrain_run WHERE id = CAST(? AS uuid)",
                String.class, runId);
    }

    // ------------------------------------------------------------------ F03

    @Test
    @DisplayName("replayAfterConflict looks the job up by session before key")
    void replayAfterConflictUsesSessionAndKeyInOrder() {
        assumeDatabase();
        String sessionId = newSession();
        String runId = newRun(sessionId);
        String key = "retry-same-key";
        RunQueueJob job = demoJob(sessionId, runId, "owner-f", key);
        jobs.insert(job);

        RunSubmissionService service = new RunSubmissionService(null, null, null, runs, jobs,
                new RunQueueSettings(), null, null, null);
        RunSubmissionService.Submission replayed = service.replayAfterConflict(RunOrigin.DEMO,
                "DEMO:owner-f", sessionId, key, job.requestDigest());

        assertEquals(runId, replayed.runId());
        assertTrue(replayed.replay());
        assertEquals(RunQueueStatus.QUEUED.name(), replayed.runStatus());

        // 同一个键换内容仍然必须拒绝（不能拿旧运行冒充新请求）。
        assertThrows(RunSubmissionException.class, () -> service.replayAfterConflict(RunOrigin.DEMO,
                "DEMO:owner-f", sessionId, key, "different-digest"));
    }

    // ------------------------------------------------------------------ fixtures

    private static void assumeDatabase() {
        org.junit.jupiter.api.Assumptions.assumeTrue(jdbc != null, skipReason);
    }

    private static ChatMessageFacadeService mockChatMessageFacade() {
        ChatMessageFacadeService facade = mock(ChatMessageFacadeService.class);
        when(facade.createChatMessage(any(ChatMessageDTO.class))).thenAnswer(invocation -> {
            ChatMessageDTO message = invocation.getArgument(0);
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO chat_message (id, session_id, role, content) "
                            + "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?)",
                    id, message.getSessionId(), message.getRole().name().toLowerCase(java.util.Locale.ROOT),
                    message.getContent());
            return CreateChatMessageResponse.builder().chatMessageId(id).build();
        });
        return facade;
    }

    private static ChatMessageDTO answer(String sessionId, String content) {
        return ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(content)
                .sessionId(sessionId)
                .build();
    }

    private static String newSession() {
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_session (id, title) VALUES (CAST(? AS uuid), ?)", sessionId, "run-guard-it");
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

    private static String runStatus(String runId) {
        return jdbc.queryForObject("SELECT status FROM exobrain_run WHERE id = CAST(? AS uuid)",
                String.class, runId);
    }

    private static String answerMessageId(String runId) {
        return jdbc.queryForObject("SELECT answer_message_id::text FROM exobrain_run WHERE id = CAST(? AS uuid)",
                String.class, runId);
    }
    /** 领取并真正开始运行：与 RunExecutor 一样把运行摘要从 QUEUED 推进到 RUNNING。 */
    private static RunQueueJob claimAndStart(String worker, String runId) {
        RunQueueJob claimed = jobs.claim(worker, 10).orElseThrow();
        assertTrue(jobs.startRun(runId, Instant.now(), 0L));
        assertEquals("RUNNING", runStatus(runId));
        return claimed;
    }

    private static int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
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
        return repositoryRoot().resolve(relative);
    }

    private static Path repoDirectory(String relative) {
        return repositoryRoot().resolve(relative);
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("deploy/migrations"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("找不到仓库根目录（deploy/migrations）");
        }
        return candidate;
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value;
    }
}
