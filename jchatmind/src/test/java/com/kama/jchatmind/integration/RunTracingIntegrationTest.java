package com.kama.jchatmind.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.AuthContext;
import com.kama.jchatmind.auth.AuthException;
import com.kama.jchatmind.auth.AuthRepository;
import com.kama.jchatmind.auth.AuthUser;
import com.kama.jchatmind.auth.ResourceAccess;
import com.kama.jchatmind.auth.ResourceAclRepository;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.demo.DemoAccessPolicy;
import com.kama.jchatmind.demo.DemoProperties;
import com.kama.jchatmind.demo.DemoSessionRepository;
import com.kama.jchatmind.execution.DemoRunService;
import com.kama.jchatmind.execution.JdbcRunRepository;
import com.kama.jchatmind.execution.RunEventRecord;
import com.kama.jchatmind.execution.RunEventStatus;
import com.kama.jchatmind.execution.RunEventType;
import com.kama.jchatmind.execution.RunOrigin;
import com.kama.jchatmind.execution.RunQueryService;
import com.kama.jchatmind.execution.RunRepository;
import com.kama.jchatmind.execution.RunStatus;
import com.kama.jchatmind.execution.RunSummary;
import com.kama.jchatmind.execution.RunViews;
import com.kama.jchatmind.execution.TokenUsage;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.models.ModelCatalogService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * 任务 05 的真实 PostgreSQL 17 + pgvector 集成检查。
 *
 * <p>沿用 {@link PostgresBaselineIntegrationTest} 的隔离方式：在配置的实例上创建一次性数据库，
 * 安装 {@code db/jchatmind.sql} 与全部迁移（含 {@code deploy/migrations/20261017-05-runs.sql}），
 * 跑完删除。未配置数据库时整体按 assumption 跳过；{@code EXOBRAIN_IT_REQUIRE_DATABASE=true} 时缺失即失败。</p>
 */
@Tag("postgres-it")
@DisplayName("Real PostgreSQL run tracing integration (05)")
class RunTracingIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String DATABASE = "exobrain_it_" + SUFFIX;
    private static final String DEMO_AGENT = "11111111-1111-4111-8111-111111111111";
    private static final String USER_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String USER_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String ADMIN = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    private static final String AGENT_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";

    private static String adminJdbcUrl;
    private static String jdbcUrl;
    private static String databaseUser;
    private static String databasePassword;
    private static boolean requireDatabase;
    private static String skipReason;
    private static Connection admin;
    private static Connection owner;

    private JdbcTemplate jdbc;
    private RunRepository runs;
    private RunQueryService queries;
    private DemoRunService demoRuns;
    private ResourceAccess access;
    private ChatSessionMapper sessions;

    @BeforeAll
    static void prepareDatabase() throws Exception {
        adminJdbcUrl = setting("exobrain.it.jdbcUrl", "EXOBRAIN_IT_JDBC_URL");
        databaseUser = setting("exobrain.it.username", "EXOBRAIN_IT_USERNAME");
        databasePassword = setting("exobrain.it.password", "EXOBRAIN_IT_PASSWORD");
        requireDatabase = Boolean.parseBoolean(setting("exobrain.it.requireDatabase", "EXOBRAIN_IT_REQUIRE_DATABASE"));
        if (isBlank(adminJdbcUrl)) {
            if (requireDatabase) {
                throw new IllegalStateException("EXOBRAIN_IT_JDBC_URL is required because "
                        + "EXOBRAIN_IT_REQUIRE_DATABASE=true");
            }
            skipReason = "No PostgreSQL configured (EXOBRAIN_IT_JDBC_URL): run tracing integration checks "
                    + "were NOT executed on this host";
            return;
        }
        try {
            admin = DriverManager.getConnection(adminJdbcUrl, databaseUser, databasePassword);
        } catch (SQLException unreachable) {
            if (requireDatabase) {
                throw new IllegalStateException("PostgreSQL is required but not reachable at " + adminJdbcUrl,
                        unreachable);
            }
            skipReason = "PostgreSQL not reachable at " + adminJdbcUrl + ": run tracing integration checks "
                    + "were NOT executed on this host";
            return;
        }
        if (!Boolean.parseBoolean(scalar(admin, "SELECT CASE WHEN rolsuper OR rolcreatedb THEN 'true' ELSE 'false' "
                + "END FROM pg_roles WHERE rolname = current_user"))) {
            String reason = "the configured role may not CREATE DATABASE, so no isolated verification database "
                    + "could be created at " + adminJdbcUrl;
            if (requireDatabase) {
                throw new IllegalStateException(reason);
            }
            skipReason = reason + ": run tracing integration checks were NOT executed on this host";
            admin.close();
            admin = null;
            return;
        }
        try (Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        jdbcUrl = adminJdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + DATABASE + "$1");
        owner = DriverManager.getConnection(jdbcUrl, databaseUser, databasePassword);
        for (Path script : installScripts()) {
            executeScript(owner, script);
        }
        // 可重复执行：迁移再跑一次（同一库）。基线脚本含种子数据，不重放（与 03 的集成检查一致）。
        for (Path script : migrations()) {
            executeScript(owner, script);
        }
        assertEquals(DATABASE, scalar(owner, "SELECT current_database()"),
                "the checks must run against the throwaway database");
        System.out.println("[postgres-it] installed " + installScripts().size()
                + " scripts twice into isolated database " + DATABASE);
    }

    private static List<Path> installScripts() {
        List<Path> scripts = new java.util.ArrayList<>();
        scripts.add(repoFile("db/jchatmind.sql"));
        scripts.addAll(migrations());
        return scripts;
    }

    private static List<Path> migrations() {
        return List.of(repoFile("deploy/migrations/20261014-accounts.sql"),
                repoFile("deploy/migrations/20261014-models.sql"),
                repoFile("deploy/migrations/20261016-storage-cleanup.sql"),
                repoFile("deploy/migrations/20261017-03-body-index.sql"),
                repoFile("deploy/migrations/20261017-05-runs.sql"));
    }

    private static Path repoFile(String relative) {
        for (Path candidate : List.of(Path.of("..", relative), Path.of(relative), Path.of("..", "..", relative))) {
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("cannot find " + relative + " from " + Path.of("").toAbsolutePath());
    }

    private static void executeScript(Connection connection, Path script) {
        String sql;
        try {
            sql = Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("cannot read " + script, unreadable);
        }
        for (String statementText : PostgresBaselineIntegrationTest.splitStatements(sql)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementText);
            } catch (SQLException failure) {
                throw new IllegalStateException("failed statement in " + script + ": "
                        + statementText.replaceAll("\\s+", " ") + " -> " + failure.getMessage(), failure);
            }
        }
    }

    @AfterAll
    static void dropDatabase() {
        if (owner != null) {
            try {
                owner.close();
            } catch (SQLException ignored) {
                // closing the scratch connection must not fail the suite
            }
        }
        if (admin != null) {
            try (Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
                System.out.println("[postgres-it] dropped isolated database " + DATABASE);
            } catch (SQLException failure) {
                System.out.println("[postgres-it] database cleanup warning: " + failure.getMessage());
            } finally {
                try {
                    admin.close();
                } catch (SQLException ignored) {
                    // best effort
                }
            }
        }
    }

    @BeforeEach
    void requireConfiguredDatabase() {
        assumeTrue(skipReason == null, skipReason);
    }

    @BeforeEach
    void wireServices() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(jdbcUrl, databaseUser, databasePassword);
        jdbc = new JdbcTemplate(source);
        runs = new JdbcRunRepository(jdbc);
        sessions = myBatisMapper(source, "mapper/ChatSessionMapper.xml", ChatSessionMapper.class);
        ChatMessageMapper messages = myBatisMapper(source, "mapper/ChatMessageMapper.xml", ChatMessageMapper.class);
        AgentMapper agents = myBatisMapper(source, "mapper/AgentMapper.xml", AgentMapper.class);
        DemoProperties properties = new DemoProperties();
        properties.setAgentId(DEMO_AGENT);
        properties.setPublicKbIds(List.of());
        properties.setShowcaseSessionIds(List.of());
        DemoAccessPolicy policy = new DemoAccessPolicy(properties);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        access = new ResourceAccess(new ResourceAclRepository(jdbc), policy, properties, agents,
                new AgentConverter(json), mock(KnowledgeBaseMapper.class), mock(DocumentMapper.class),
                sessions, messages, mock(com.kama.jchatmind.auth.AuthService.class),
                mock(ModelCatalogService.class));
        queries = new RunQueryService(runs, access, 300);
        demoRuns = new DemoRunService(policy, new DemoSessionRepository(jdbc), queries);
        jdbc.update("INSERT INTO exobrain_model (id, name, kind, protocol, base_url, provider_model, "
                        + "enabled, default_model) VALUES ('deepseek-chat', 'DeepSeek Chat', 'LLM', 'deepseek', "
                        + "'https://example.invalid', 'deepseek-chat', TRUE, TRUE) ON CONFLICT (id) DO NOTHING");
        for (String agent : List.of(AGENT_ID, DEMO_AGENT)) {
            jdbc.update("INSERT INTO agent (id, name, model, allowed_kbs, allowed_tools, chat_options) "
                            + "VALUES (CAST(? AS uuid), '合成助手', 'deepseek-chat', '[]'::jsonb, '[]'::jsonb, "
                            + "'{\"temperature\":0.7,\"topP\":1.0,\"messageLength\":10}'::jsonb) "
                            + "ON CONFLICT (id) DO NOTHING",
                    agent);
        }
        for (String user : List.of(USER_A, USER_B, ADMIN)) {
            jdbc.update("INSERT INTO exobrain_user (id, username, display_name, role, password_hash) "
                            + "VALUES (CAST(? AS uuid), ?, ?, ?, 'x') ON CONFLICT (id) DO NOTHING",
                    user, "user-" + user.substring(0, 8), "合成用户", ADMIN.equals(user) ? "ADMIN" : "USER");
        }
        actAs(USER_A, "USER");
    }

    @AfterEach
    void clearAuth() {
        setContext(null);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("摘要与事件 round-trip，事件按 sequence 排序且 (run_id, sequence) 唯一")
    void summaryAndEventsRoundTrip() {
        String sessionId = accountSession(USER_A);
        String messageId = insertUserMessage(sessionId);
        String runId = insertRun(sessionId, messageId, RunOrigin.ACCOUNT, RunStatus.RUNNING, Instant.now());
        Instant started = Instant.now().minusMillis(1500).truncatedTo(ChronoUnit.MILLIS);

        runs.appendEvent(new RunEventRecord(UUID.randomUUID().toString(), runId, 1, RunEventType.MODEL_CALL,
                RunEventStatus.SUCCEEDED, started, 1200L, null, null, null, 1, null, null));
        runs.appendEvent(new RunEventRecord(UUID.randomUUID().toString(), runId, 2, RunEventType.TOOL_CALL,
                RunEventStatus.FAILED, started, 300L, "call-1", "failingTool", null, null, "TOOL_FAILED", null));
        runs.appendEvent(new RunEventRecord(UUID.randomUUID().toString(), runId, 3, RunEventType.RETRIEVAL,
                RunEventStatus.SUCCEEDED, started, 300L, "call-1", null, "ret-1", null, null, "OK"));

        RunSummary stored = runs.findById(runId);
        assertNotNull(stored);
        assertEquals(RunStatus.RUNNING, stored.status());
        assertEquals(RunOrigin.ACCOUNT, stored.origin());
        assertEquals(AGENT_ID, stored.agentId());
        assertEquals("合成助手", stored.agentNameAtStart());
        assertEquals(64, stored.agentConfigHash().length(), "only the config hash is stored");
        assertEquals("deepseek-chat", stored.modelCatalogId());
        assertNull(stored.inputTokens(), "unknown usage stays null");

        List<RunEventRecord> events = runs.findEvents(runId);
        assertEquals(List.of(1, 2, 3), events.stream().map(RunEventRecord::sequence).toList());
        assertEquals(RunEventType.TOOL_CALL, events.get(1).type());
        assertEquals("call-1", events.get(1).toolCallId());
        assertEquals("ret-1", events.get(2).retrievalId());
        assertEquals(1200L, events.get(0).durationMs());

        assertThrows(DataAccessException.class, () -> runs.appendEvent(new RunEventRecord(
                        UUID.randomUUID().toString(), runId, 1, RunEventType.MODEL_CALL, RunEventStatus.SUCCEEDED,
                        started, 1L, null, null, null, 1, null, null)),
                "the same (run_id, sequence) must not produce a second timeline entry");
    }

    @Test
    @DisplayName("终态不会被迟到的 RUNNING 覆盖，重复终态幂等，负耗时被拒绝")
    void statusTransitionsAreGuarded() {
        String sessionId = accountSession(USER_A);
        String runId = insertRun(sessionId, null, RunOrigin.ACCOUNT, RunStatus.RUNNING, Instant.now());

        assertEquals(1, runs.complete(runId, RunStatus.SUCCEEDED, Instant.now(), 1500L,
                TokenUsage.of(11, 5, null), null, false));
        assertEquals(RunStatus.SUCCEEDED, runs.findById(runId).status());
        assertEquals(16, runs.findById(runId).totalTokens());

        assertEquals(0, runs.complete(runId, RunStatus.FAILED, Instant.now(), 10L, null, "EXECUTION_FAILED", false),
                "a terminal run must not be rewritten by a late failure");
        assertEquals(1, runs.complete(runId, RunStatus.SUCCEEDED, Instant.now(), 1500L,
                TokenUsage.of(11, 5, null), null, false), "the same terminal write is idempotent");
        assertEquals(RunStatus.SUCCEEDED, runs.findById(runId).status());

        assertEquals(0, jdbc.update("UPDATE exobrain_run SET status = 'RUNNING' WHERE id = CAST(? AS uuid) "
                + "AND status IN ('QUEUED', 'RUNNING')", runId));
        assertEquals(RunStatus.SUCCEEDED, runs.findById(runId).status());

        String other = insertRun(sessionId, null, RunOrigin.ACCOUNT, RunStatus.RUNNING, Instant.now());
        assertEquals(1, runs.complete(other, RunStatus.INTERRUPTED, Instant.now(), null, null,
                "INTERRUPTED_BY_RESTART", false));
        assertEquals(RunStatus.INTERRUPTED, runs.findById(other).status());

        assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO exobrain_run (id, session_id, "
                        + "status, origin, submitted_at, duration_ms) VALUES (CAST(? AS uuid), CAST(? AS uuid), "
                        + "'RUNNING', 'ACCOUNT', NOW(), -1)", UUID.randomUUID().toString(), sessionId),
                "negative durations are rejected by the schema");
    }

    @Test
    @DisplayName("重启遗留的 RUNNING 以“中断/未知”呈现，数据库里仍是 RUNNING")
    void staleRunningIsPresentedAsInterrupted() {
        String sessionId = accountSession(USER_A);
        String runId = insertRun(sessionId, null, RunOrigin.ACCOUNT, RunStatus.RUNNING, Instant.now());
        String freshId = insertRun(sessionId, null, RunOrigin.ACCOUNT, RunStatus.RUNNING, Instant.now());
        jdbc.update("UPDATE exobrain_run SET heartbeat_at = NOW() - INTERVAL '10 minutes' "
                + "WHERE id = CAST(? AS uuid)", runId);

        RunViews.RunList list = queries.listForAccountSession(sessionId);

        RunViews.Summary stale = list.runs().stream().filter(run -> run.runId().equals(runId)).findFirst().orElseThrow();
        RunViews.Summary fresh = list.runs().stream().filter(run -> run.runId().equals(freshId)).findFirst().orElseThrow();
        assertEquals("INTERRUPTED", stale.status());
        assertTrue(stale.interrupted());
        assertEquals("RUNNING", fresh.status());
        assertFalse(fresh.interrupted());
        assertEquals(RunStatus.RUNNING, runs.findById(runId).status(),
                "the stored status is not rewritten by a read");

        runs.appendEvent(new RunEventRecord(UUID.randomUUID().toString(), runId, 1, RunEventType.MODEL_CALL,
                RunEventStatus.SUCCEEDED, Instant.now(), 10L, null, null, null, 1, null, null));
        assertEquals("INTERRUPTED", queries.detail(runs.findById(runId)).summary().status(),
                "a late progress read still never presents the run as running");
    }

    @Test
    @DisplayName("账号读取矩阵：两个用户 + 管理员，管理员没有查看他人运行的隐式权限")
    void accountAccessMatrix() {
        String sessionA = accountSession(USER_A);
        String sessionB = accountSession(USER_B);
        String sessionAdmin = accountSession(ADMIN);
        String runA = insertRun(sessionA, null, RunOrigin.ACCOUNT, RunStatus.SUCCEEDED, Instant.now());
        String runB = insertRun(sessionB, null, RunOrigin.ACCOUNT, RunStatus.SUCCEEDED, Instant.now());
        String runAdmin = insertRun(sessionAdmin, null, RunOrigin.ACCOUNT, RunStatus.SUCCEEDED, Instant.now());

        actAs(USER_A, "USER");
        assertEquals(List.of(runA), queries.listForAccountSession(sessionA).runs().stream()
                .map(RunViews.Summary::runId).toList());
        assertEquals(runA, queries.findForAccount(runA).summary().runId());
        assertMissing(() -> queries.listForAccountSession(sessionB));
        assertMissing(() -> queries.findForAccount(runB));
        assertMissing(() -> queries.findForAccount(runAdmin));

        actAs(USER_B, "USER");
        assertEquals(runB, queries.findForAccount(runB).summary().runId());
        assertMissing(() -> queries.findForAccount(runA));

        actAs(ADMIN, "ADMIN");
        assertEquals(runAdmin, queries.findForAccount(runAdmin).summary().runId());
        assertMissing(() -> queries.listForAccountSession(sessionA));
        assertMissing(() -> queries.findForAccount(runA));
        assertMissing(() -> queries.findForAccount(runB));
    }

    @Test
    @DisplayName("游客读取矩阵：两个访客各读自己的运行；公开精选走字段白名单")
    void guestAccessMatrix() throws Exception {
        String ownerOne = "1".repeat(64);
        String ownerTwo = "2".repeat(64);
        String sessionOne = demoSession(ownerOne);
        String sessionTwo = demoSession(ownerTwo);
        String showcase = demoSession(ownerTwo);
        String runOne = insertRun(sessionOne, null, RunOrigin.DEMO, RunStatus.SUCCEEDED, Instant.now());
        String runTwo = insertRun(sessionTwo, null, RunOrigin.DEMO, RunStatus.SUCCEEDED, Instant.now());
        String runShowcase = insertRun(showcase, null, RunOrigin.DEMO, RunStatus.SUCCEEDED, Instant.now());
        // 把精选会话加入发布白名单（只读展示）。
        demoRuns = new DemoRunService(showcasePolicy(showcase), new DemoSessionRepository(jdbc), queries);

        assertEquals(List.of(runOne), demoRuns.list(ownerOne, sessionOne).runs().stream()
                .map(RunViews.PublicSummary::runId).toList());
        assertEquals(runOne, demoRuns.find(ownerOne, runOne).summary().runId());
        assertDemoMissing(() -> demoRuns.list(ownerOne, sessionTwo));
        assertDemoMissing(() -> demoRuns.find(ownerOne, runTwo));

        assertEquals(runTwo, demoRuns.find(ownerTwo, runTwo).summary().runId());
        assertDemoMissing(() -> demoRuns.find(ownerTwo, runOne));

        // 另一位访客可以读取公开精选会话的运行记录，但只拿到白名单字段。
        RunViews.PublicDetail publicRun = demoRuns.find(ownerOne, runShowcase);
        assertEquals(runShowcase, publicRun.summary().runId());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(publicRun);
        assertFalse(json.contains(showcase), "the session id is not part of the public projection");
        assertFalse(json.contains(AGENT_ID), "the agent id is not part of the public projection");
        assertFalse(json.contains("agentConfigHash"), "the config hash is not part of the public projection");
        assertFalse(json.contains("modelCatalogId"), "the model catalog id is not part of the public projection");

        // 账号运行不能通过游客路径读取。
        String otherAccountSession = accountSession(USER_A);
        String accountRun = insertRun(otherAccountSession, null, RunOrigin.ACCOUNT, RunStatus.SUCCEEDED, Instant.now());
        assertDemoMissing(() -> demoRuns.find(ownerOne, accountRun));
    }

    // ------------------------------------------------------------------ fixtures

    private DemoAccessPolicy showcasePolicy(String showcaseSessionId) {
        DemoProperties properties = new DemoProperties();
        properties.setAgentId(DEMO_AGENT);
        properties.setPublicKbIds(List.of());
        properties.setShowcaseSessionIds(List.of(showcaseSessionId));
        return new DemoAccessPolicy(properties);
    }

    private String accountSession(String ownerId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_session (id, agent_id, title) VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)",
                id, AGENT_ID, "合成会话");
        jdbc.update("INSERT INTO exobrain_resource_acl (resource_type, resource_id, owner_id) "
                + "VALUES ('SESSION', CAST(? AS uuid), CAST(? AS uuid))", id, ownerId);
        return id;
    }

    private String demoSession(String demoOwner) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_session (id, agent_id, title, metadata) VALUES (CAST(? AS uuid), "
                        + "CAST(? AS uuid), '合成会话', jsonb_build_object('demoOwner', CAST(? AS text)))",
                id, DEMO_AGENT, demoOwner);
        return id;
    }

    private String insertUserMessage(String sessionId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_message (id, session_id, role, content) "
                + "VALUES (CAST(? AS uuid), CAST(? AS uuid), 'user', '问题')", id, sessionId);
        return id;
    }

    private String insertRun(String sessionId, String triggeringMessageId, RunOrigin origin, RunStatus status,
                             Instant submittedAt) {
        String runId = UUID.randomUUID().toString();
        runs.create(new RunSummary(RunSummary.SCHEMA_VERSION, runId, sessionId, triggeringMessageId, AGENT_ID,
                "合成助手", "0".repeat(64), "deepseek-chat", "deepseek-chat", status, submittedAt,
                submittedAt, status.isTerminal() ? submittedAt : null, status.isTerminal() ? 10L : null,
                0L, null, null, null, null, null, false, submittedAt, origin));
        return runId;
    }

    private static void actAs(String userId, String role) {
        setContext(new AuthRepository.LoginSession(
                new AuthUser(userId, "user-" + userId.substring(0, 8), "合成用户", role, true, Instant.now()),
                "token-" + userId, "csrf", Instant.now().plusSeconds(600), 1));
    }

    @SuppressWarnings("unchecked")
    private static void setContext(AuthRepository.LoginSession session) {
        try {
            Field field = AuthContext.class.getDeclaredField("CURRENT");
            field.setAccessible(true);
            ThreadLocal<AuthRepository.LoginSession> current =
                    (ThreadLocal<AuthRepository.LoginSession>) field.get(null);
            if (session == null) {
                current.remove();
            } else {
                current.set(session);
            }
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException("AuthContext test hook is unavailable", impossible);
        }
    }

    private static void assertMissing(org.junit.jupiter.api.function.Executable call) {
        AuthException error = assertThrows(AuthException.class, call);
        assertEquals(404, error.getStatus(), "unauthorized runs are reported as missing");
    }

    private static void assertDemoMissing(org.junit.jupiter.api.function.Executable call) {
        com.kama.jchatmind.demo.DemoException error =
                assertThrows(com.kama.jchatmind.demo.DemoException.class, call);
        assertEquals(404, error.getStatus(), "unauthorized guest runs are reported as missing");
    }

    private static <T> T myBatisMapper(DataSource source, String resource, Class<T> type) throws IOException {
        Configuration configuration = new Configuration(new Environment("run-tracing-it",
                new SpringManagedTransactionFactory(), source));
        configuration.getTypeHandlerRegistry().register("com.kama.jchatmind.typehandler");
        try (var stream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory).getMapper(type);
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property);
        if (isBlank(value)) {
            value = System.getenv(environment);
        }
        return value == null ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }
}