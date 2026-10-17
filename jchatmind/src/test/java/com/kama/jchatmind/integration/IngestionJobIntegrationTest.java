package com.kama.jchatmind.integration;

import com.kama.jchatmind.demo.DemoAccessPolicy;
import com.kama.jchatmind.demo.DemoProperties;
import com.kama.jchatmind.ingestion.IngestionAccessGuard;
import com.kama.jchatmind.ingestion.IngestionJob;
import com.kama.jchatmind.ingestion.IngestionJobRepository;
import com.kama.jchatmind.ingestion.IngestionJobRunner;
import com.kama.jchatmind.ingestion.IngestionJobView;
import com.kama.jchatmind.ingestion.IngestionPublisher;
import com.kama.jchatmind.ingestion.IngestionService;
import com.kama.jchatmind.ingestion.IngestionServiceImpl;
import com.kama.jchatmind.ingestion.IngestionSettings;
import com.kama.jchatmind.ingestion.IngestionStatus;
import com.kama.jchatmind.ingestion.IngestionWorker;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.models.ModelDefinition;
import com.kama.jchatmind.models.ModelException;
import com.kama.jchatmind.models.ModelKind;
import com.kama.jchatmind.models.ModelRuntimeService;
import com.kama.jchatmind.retrieval.IndexPublisher;
import com.kama.jchatmind.retrieval.LegacyTitleIndexer;
import com.kama.jchatmind.retrieval.RetrievalIndexRepository;
import com.kama.jchatmind.retrieval.RetrievalIndexService;
import com.kama.jchatmind.retrieval.RetrievalIndexServiceImpl;
import com.kama.jchatmind.retrieval.RetrievalSettings;
import com.kama.jchatmind.retrieval.SourceText;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务 06 的真实 PostgreSQL 17 + pgvector 集成验证：任务领取、租约/fencing、有限重试、删除竞态与
 * 发布失败保留旧索引。所有向量由确定性桩生成（同一文本 → 同一向量），只验证状态机、原子性与资源边界。
 *
 * <p>沿用 {@link PostgresBaselineIntegrationTest} 的隔离方式：在配置的实例上创建一次性数据库
 * {@code exobrain_it_<随机>}，安装基线脚本与全部迁移（含本任务的 {@code 20261017-06-ingestion.sql}），
 * 跑完删除。未配置数据库时按 assumption 跳过；{@code EXOBRAIN_IT_REQUIRE_DATABASE=true} 时缺失即失败。</p>
 */
@Tag("postgres-it")
@DisplayName("Real PostgreSQL durable ingestion integration (06)")
class IngestionJobIntegrationTest {

    private static final int DIMENSIONS = 1024;
    private static final String MODEL_ID = "it-embedding";
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String DATABASE = "exobrain_it_" + SUFFIX;

    @TempDir
    static Path storageRoot;

    private static String adminJdbcUrl;
    private static String jdbcUrl;
    private static String databaseUser;
    private static String databasePassword;
    private static boolean requireDatabase;
    private static String skipReason;
    private static Connection admin;
    private static Connection owner;

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private ChunkBgeM3Mapper chunks;
    private IngestionJobRepository jobs;
    private IngestionPublisher publisher;
    private IngestionJobRunner runner;
    private IngestionWorker worker;
    private IngestionService ingestion;
    private RetrievalIndexRepository indexRepository;
    private ModelCatalogService catalog;
    private ModelRuntimeService runtime;
    private DocumentStorageService storage;
    private final IngestionSettings settings = new IngestionSettings();
    private final java.util.concurrent.atomic.AtomicReference<RuntimeException> embedFailure =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> embedHook =
            new java.util.concurrent.atomic.AtomicReference<>();

    // ------------------------------------------------------------------ harness

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
            skipReason = "No PostgreSQL configured (EXOBRAIN_IT_JDBC_URL): durable ingestion integration checks "
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
            skipReason = "PostgreSQL not reachable at " + adminJdbcUrl + ": durable ingestion integration checks "
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
            skipReason = reason + ": durable ingestion integration checks were NOT executed on this host";
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
        assertEquals(DATABASE, scalar(owner, "SELECT current_database()"),
                "the checks must run against the throwaway database");
        System.out.println("[postgres-it] installed " + installScripts().size()
                + " scripts into isolated database " + DATABASE);
    }

    private static List<Path> installScripts() {
        return List.of(repoFile("db/jchatmind.sql"),
                repoFile("deploy/migrations/20261014-accounts.sql"),
                repoFile("deploy/migrations/20261014-models.sql"),
                repoFile("deploy/migrations/20261016-storage-cleanup.sql"),
                repoFile("deploy/migrations/20261017-03-body-index.sql"),
                repoFile("deploy/migrations/20261017-06-ingestion.sql"));
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
    void wireServices() throws Exception {
        assumeTrue(skipReason == null, skipReason);
        DriverManagerDataSource source = new DriverManagerDataSource(jdbcUrl, databaseUser, databasePassword);
        jdbc = new JdbcTemplate(source);
        PlatformTransactionManager manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        chunks = myBatisMapper(source);
        indexRepository = new RetrievalIndexRepository(jdbc);
        catalog = mock(ModelCatalogService.class);
        when(catalog.embeddingForKnowledgeBase(anyString())).thenReturn(embeddingModel());
        // 有界故障注入：失败用一个可切换的引用，钩子在第一次向量调用时执行一次副作用。
        embedFailure.set(null);
        embedHook.set(null);
        runtime = mock(ModelRuntimeService.class);
        when(runtime.embed(any(), anyString())).thenAnswer(invocation -> {
            RuntimeException failure = embedFailure.get();
            if (failure != null) {
                throw failure;
            }
            java.util.function.Consumer<String> hook = embedHook.get();
            if (hook != null) {
                embedHook.set(null);
                hook.accept(invocation.getArgument(1, String.class));
            }
            return stubVector(invocation.getArgument(1, String.class));
        });
        storage = mock(DocumentStorageService.class);
        when(storage.getFilePath(anyString()))
                .thenAnswer(invocation -> storageRoot.resolve(invocation.getArgument(0, String.class)));

        IndexPublisher indexPublisher = transactional(new IndexPublisher(indexRepository, chunks, catalog), manager);
        RetrievalIndexService indexService = new RetrievalIndexServiceImpl(indexRepository, indexPublisher, catalog,
                runtime, new RetrievalSettings());
        jobs = new IngestionJobRepository(jdbc);
        IngestionAccessGuard guard = new IngestionAccessGuard(jdbc, new DemoAccessPolicy(new DemoProperties()));
        LegacyTitleIndexer legacy = new LegacyTitleIndexer(mock(MarkdownParserService.class), mock(RagService.class),
                chunks);
        publisher = transactional(new IngestionPublisher(jobs, guard, indexService, legacy), manager);
        runner = new IngestionJobRunner(jobs, guard, publisher, indexService, catalog, runtime, storage, settings);
        worker = new IngestionWorker(jobs, runner, settings);
        ingestion = new IngestionServiceImpl(jobs, settings);
        settings.setMaxAttempts(3);
        settings.setRetryBaseSeconds(1);
        settings.setLeaseSeconds(120);
        ensureEmbeddingModelRow();
        // 每个测试只处理自己创建的导入任务：避免上一个用例遗留的任务被 worker 先领取。
        jdbc.update("DELETE FROM exobrain_ingestion_job");
    }

    private static ChunkBgeM3Mapper myBatisMapper(DataSource source) throws IOException {
        Configuration configuration = new Configuration(new Environment("ingestion-it",
                new SpringManagedTransactionFactory(), source));
        configuration.getTypeHandlerRegistry().register("com.kama.jchatmind.typehandler");
        try (var stream = Resources.getResourceAsStream("mapper/ChunkBgeM3Mapper.xml")) {
            new XMLMapperBuilder(stream, configuration, "mapper/ChunkBgeM3Mapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory).getMapper(ChunkBgeM3Mapper.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, PlatformTransactionManager manager) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private static ModelDefinition embeddingModel() {
        return new ModelDefinition(MODEL_ID, "自动测试向量模型", ModelKind.EMBEDDING, "openai",
                "https://example.invalid", "stub-embedding", null, true, true, DIMENSIONS,
                Instant.now(), Instant.now());
    }

    private void ensureEmbeddingModelRow() {
        jdbc.update("INSERT INTO exobrain_model (id, name, kind, protocol, base_url, provider_model, enabled, "
                        + "default_model, dimensions) VALUES (?, '自动测试向量模型', 'EMBEDDING', 'openai', "
                        + "'https://example.invalid', 'stub-embedding', TRUE, TRUE, ?) ON CONFLICT (id) DO NOTHING",
                MODEL_ID, DIMENSIONS);
    }

    private static float[] stubVector(String text) {
        float[] vector = new float[DIMENSIONS];
        vector[0] = 1.0f;
        vector[Math.abs(text.hashCode()) % (DIMENSIONS - 1) + 1] = 0.01f;
        return vector;
    }

    private static String literal(float[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int dimension = 0; dimension < vector.length; dimension++) {
            if (dimension > 0) {
                builder.append(',');
            }
            builder.append(vector[dimension]);
        }
        return builder.append(']').toString();
    }

    private static String scalar(Connection connection, String sql) {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                return null;
            }
            Object value = rows.getObject(1);
            return value == null ? null : value.toString();
        } catch (SQLException failure) {
            throw new IllegalStateException("query failed: " + sql, failure);
        }
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property);
        return isBlank(value) ? System.getenv(environment) : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String count(String sql) {
        List<String> values = jdbc.query(sql, (rs, row) -> rs.getString(1));
        return values.isEmpty() ? null : values.get(0);
    }

    // ------------------------------------------------------------------ fixtures

    private String insertUser(boolean enabled) {
        String userId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO exobrain_user (id, username, display_name, role, enabled, password_hash) "
                        + "VALUES (CAST(? AS uuid), ?, '导入用户', 'USER', ?, 'x')",
                userId, "it-" + userId.substring(0, 8), enabled);
        return userId;
    }

    private String insertKnowledgeBase(String ownerUserId) {
        String kbId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO knowledge_base (id, name) VALUES (CAST(? AS uuid), ?)", kbId, "导入库-" + SUFFIX);
        jdbc.update("INSERT INTO exobrain_kb_embedding (kb_id, model_id) VALUES (CAST(? AS uuid), ?)", kbId, MODEL_ID);
        jdbc.update("INSERT INTO exobrain_resource_acl (resource_type, resource_id, owner_id) "
                + "VALUES ('KB', CAST(? AS uuid), CAST(? AS uuid))", kbId, ownerUserId);
        return kbId;
    }

    private String insertDocument(String kbId, String filename, String markdown) throws IOException {
        String documentId = UUID.randomUUID().toString();
        String relative = kbId + "/" + documentId + "/" + filename;
        Path file = storageRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, markdown, StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO document (id, kb_id, filename, filetype, size, metadata) VALUES "
                        + "(CAST(? AS uuid), CAST(? AS uuid), ?, 'md', ?, CAST(? AS jsonb))",
                documentId, kbId, filename, (long) markdown.getBytes(StandardCharsets.UTF_8).length,
                "{\"filePath\":\"" + relative + "\"}");
        return documentId;
    }

    private String submitJob(String kbId, String documentId, String actorId) {
        String filePath = documentId == null ? null : kbId + "/" + documentId + "/doc.md";
        String hash = SourceText.normalize("# 标题\n正文内容。\n".getBytes(StandardCharsets.UTF_8)).sha256();
        return ingestion.submit(kbId, documentId, filePath, hash, actorId);
    }

    /** 提交并返回任务 id，文件内容与 submitJob 的 hash 一致。 */
    private String submitIngestibleJob(String kbId, String documentId, String actorId) throws IOException {
        String markdown = "# 标题\n正文内容。\n";
        // insertDocument 已经把文件写到 <kb>/<doc>/doc.md（见调用方），这里只登记任务。
        return ingestion.submit(kbId, documentId, kbId + "/" + documentId + "/doc.md",
                SourceText.normalize(markdown.getBytes(StandardCharsets.UTF_8)).sha256(), actorId);
    }

    private String insertIngestibleDocument(String kbId, String filename) throws IOException {
        String markdown = "# 标题\n正文内容。\n";
        String documentId = UUID.randomUUID().toString();
        String relative = kbId + "/" + documentId + "/doc.md";
        Path file = storageRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, markdown, StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO document (id, kb_id, filename, filetype, size, metadata) VALUES "
                        + "(CAST(? AS uuid), CAST(? AS uuid), ?, 'md', ?, CAST(? AS jsonb))",
                documentId, kbId, filename, (long) markdown.getBytes(StandardCharsets.UTF_8).length,
                "{\"filePath\":\"" + relative + "\"}");
        return documentId;
    }

    private String jobStatus(String jobId) {
        return jdbc.queryForObject("SELECT status FROM exobrain_ingestion_job WHERE id = CAST(? AS uuid)",
                String.class, jobId);
    }

    private String jobErrorCode(String jobId) {
        return jdbc.queryForObject("SELECT error_code FROM exobrain_ingestion_job WHERE id = CAST(? AS uuid)",
                String.class, jobId);
    }

    private String activeRevision(String kbId) {
        return count("SELECT active_revision_id FROM exobrain_kb_index_state WHERE kb_id = CAST('" + kbId
                + "' AS uuid)");
    }

    private String chunkCount(String kbId, String documentId) {
        return count("SELECT count(*) FROM chunk_bge_m3 WHERE kb_id = CAST('" + kbId
                + "' AS uuid) AND doc_id = CAST('" + documentId + "' AS uuid)");
    }

    private void forceDue(String jobId) {
        jdbc.update("UPDATE exobrain_ingestion_job SET next_attempt_at = NOW() - INTERVAL '1 second' "
                + "WHERE id = CAST(? AS uuid)", jobId);
    }

    private void expireLease(String jobId) {
        jdbc.update("UPDATE exobrain_ingestion_job SET lease_expires_at = NOW() - INTERVAL '1 second' "
                + "WHERE id = CAST(? AS uuid)", jobId);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("the migration creates the job table and re-applying every script keeps it usable")
    void migrationCreatesJobTableAndIsRepeatable() throws Exception {
        assertEquals("1", count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name = 'exobrain_ingestion_job'"));
        assertEquals("1", count("SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' "
                + "AND indexname = 'exobrain_ingestion_job_active'"));
        String userId = insertUser(true);
        String kbId = insertKnowledgeBase(userId);
        String documentId = insertIngestibleDocument(kbId, "重复迁移.md");
        String jobId = submitIngestibleJob(kbId, documentId, userId);

        // 基线脚本的 agent 种子数据有到 exobrain_model 的外键；补一行让整组脚本可重放（与任务 03 相同）。
        jdbc.update("INSERT INTO exobrain_model (id, name, kind, protocol, base_url, provider_model, enabled, "
                + "default_model, dimensions) VALUES ('deepseek-chat', 'DeepSeek Chat', 'LLM', 'deepseek', "
                + "'https://api.deepseek.com', 'deepseek-chat', TRUE, FALSE, NULL) ON CONFLICT (id) DO NOTHING");
        for (Path script : installScripts()) {
            executeScript(owner, script);
        }

        assertEquals("QUEUED", jobStatus(jobId));
        assertEquals("1", count("SELECT count(*) FROM exobrain_ingestion_job WHERE id = CAST('" + jobId
                + "' AS uuid)"));
    }

    @Test
    @DisplayName("a submitted job is claimed, published atomically and no longer claimable")
    void submittedJobIsIndexedAndPublished() throws Exception {
        String userId = insertUser(true);
        String kbId = insertKnowledgeBase(userId);
        String documentId = insertIngestibleDocument(kbId, "第一份.md");
        String jobId = submitIngestibleJob(kbId, documentId, userId);

        assertTrue(worker.runOne());

        assertEquals("SUCCEEDED", jobStatus(jobId));
        String revisionId = activeRevision(kbId);
        assertNotNull(revisionId);
        assertEquals("1", count("SELECT count(*) FROM exobrain_index_revision WHERE id = CAST('" + revisionId
                + "' AS uuid) AND status = 'PUBLISHED'"));
        assertTrue(Integer.parseInt(chunkCount(kbId, documentId)) > 0);
        assertEquals("0", count("SELECT count(*) FROM chunk_bge_m3 WHERE kb_id = CAST('" + kbId
                + "' AS uuid) AND revision_id IS NULL"), "no legacy rows are written by the async path");
        assertEquals(count("SELECT chunks_total FROM exobrain_ingestion_job WHERE id = CAST('" + jobId
                        + "' AS uuid)"),
                count("SELECT chunks_done FROM exobrain_ingestion_job WHERE id = CAST('" + jobId + "' AS uuid)"),
                "progress must report the real chunk counts, not an estimate");
        assertFalse(worker.runOne(), "a finished job must not be claimable again");
    }

    @Test
    @DisplayName("a job committed but not started is reclaimed after a restart, and the old lease cannot write")
    void committedJobIsReclaimedAfterRestart() throws Exception {
        String userId = insertUser(true);
        String kbId = insertKnowledgeBase(userId);
        String documentId = insertIngestibleDocument(kbId, "重启.md");
        String jobId = submitIngestibleJob(kbId, documentId, userId);

        IngestionJob first = jobs.claim("worker-a", settings.leaseSeconds()).orElseThrow();
        assertEquals(1, first.attempts());
        expireLease(jobId);

        IngestionJob second = jobs.claim("worker-b", settings.leaseSeconds()).orElseThrow();
        assertEquals(jobId, second.id());
        assertEquals(2, second.attempts());
        assertEquals(first.fencingToken() + 1, second.fencingToken());

        assertFalse(jobs.finish(jobId, first.fencingToken(), IngestionStatus.FAILED,
                com.kama.jchatmind.ingestion.IngestionErrorCode.INTERNAL_ERROR),
                "the expired worker must not be able to write a terminal state");
        assertTrue(jobs.finish(jobId, second.fencingToken(), IngestionStatus.FAILED,
                com.kama.jchatmind.ingestion.IngestionErrorCode.INTERNAL_ERROR));
        assertEquals("FAILED", jobStatus(jobId));
    }

    @Test
    @DisplayName("two workers racing for the same job produce exactly one valid lease")
    void twoWorkersRaceForOneJob() throws Exception {
        String userId = insertUser(true);
        String kbId = insertKnowledgeBase(userId);
        String documentId = insertIngestibleDocument(kbId, "竞争.md");
        String jobId = submitIngestibleJob(kbId, documentId, userId);

        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<IngestionJob>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                String owner = "worker-" + index;
                Callable<IngestionJob> race = () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return jobs.claim(owner, settings.leaseSeconds()).orElse(null);
                };
                futures.add(pool.submit(race));
            }
            start.countDown();
            List<IngestionJob> winners = new ArrayList<>();
            for (Future<IngestionJob> future : futures) {
                IngestionJob claimed = future.get(15, TimeUnit.SECONDS);
                if (claimed != null) {
                    winners.add(claimed);
                }
            }
            assertEquals(1, winners.size(), "only one worker may hold the lease");
            assertEquals(jobId, winners.get(0).id());
            assertEquals("1", count("SELECT count(*) FROM exobrain_ingestion_job WHERE id = CAST('" + jobId
                    + "' AS uuid) AND fencing_token = 1"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a transient embedding failure waits and then publishes exactly one set of chunks")
    void transientEmbeddingFailureRetriesThenSucceeds() throws Exception {
        String userId = insertUser(true);
        String kbId = insertKnowledgeBase(userId);
        String documentId = insertIngestibleDocument(kbId, "临时失败.md");
        String jobId = submitIngestibleJob(kbId, documentId, userId);

        embedFailure.set(new ModelException(502, "向量服务暂不可用"));
        assertTrue(worker.runOne());

        assertEquals("RETRY_WAIT", jobStatus(jobId));
        assertEquals("EMBEDDING_UNAVAILABLE", jobErrorCode(jobId));
        assertEquals("0", chunkCount(kbId, documentId), "a failed attempt must not leave retrievable chunks");
        assertNull(activeRevision(kbId));

        embedFailure.set(null);
        forceDue(jobId);
        assertTrue(worker.runOne());

        assertEquals("SUCCEEDED", jobStatus(jobId));
        assertNotNull(activeRevision(kbId));
        assertEquals("1", count("SELECT count(*) FROM exobrain_index_revision WHERE kb_id = CAST('" + kbId
                + "' AS uuid) AND status = 'PUBLISHED'"));
        assertEquals(count("SELECT chunks_total FROM exobrain_ingestion_job WHERE id = CAST('" + jobId
                        + "' AS uuid)"),
                chunkCount(kbId, documentId));
    }

    @Test
    @DisplayName("a permanently rejected embedding request fails without retrying or publishing")
    void permanentEmbeddingErrorFailsWithoutIndex() throws Exception {
        String userId = insertUser(true);
        String kbId = insertKnowledgeBase(userId);
        String documentId = insertIngestibleDocument(kbId, "永久失败.md");
        String jobId = submitIngestibleJob(kbId, documentId, userId);

        embedFailure.set(new ModelException(400, "向量查询内容不能为空"));
        assertTrue(worker.runOne());

        assertEquals("FAILED", jobStatus(jobId));
        assertEquals("EMBEDDING_REJECTED", jobErrorCode(jobId));
        assertEquals("0", chunkCount(kbId, documentId));
        assertNull(activeRevision(kbId));
        assertEquals("0", count("SELECT count(*) FROM exobrain_index_revision WHERE kb_id = CAST('" + kbId
                + "' AS uuid)"), "a rejected candidate must not leave a DRAFT revision behind");
    }

    @Test
    @DisplayName("deleting the document during the build publishes nothing and keeps the old index")
    void documentDeletedDuringBuildPublishesNothing() throws Exception {
        String userId = insertUser(true);
        String kbId = insertKnowledgeBase(userId);
        String first = insertIngestibleDocument(kbId, "已发布.md");
        String firstJob = submitIngestibleJob(kbId, first, userId);
        assertTrue(worker.runOne());
        String baselineRevision = activeRevision(kbId);
        assertNotNull(baselineRevision);

        String second = insertIngestibleDocument(kbId, "构建中删除.md");
        String secondJob = submitIngestibleJob(kbId, second, userId);
        // 有界故障注入：第一次向量调用成功后删除文档（同时级联删除任务行）。
        embedHook.set(text -> jdbc.update("DELETE FROM document WHERE id = CAST(? AS uuid)", second));

        worker.runOne();

        assertEquals("0", count("SELECT count(*) FROM exobrain_ingestion_job WHERE id = CAST('" + secondJob
                + "' AS uuid)"), "the job row is removed with the document, so it cannot be revived");
        assertEquals(baselineRevision, activeRevision(kbId), "the previously published index stays active");
        assertEquals("1", count("SELECT count(*) FROM exobrain_index_revision WHERE id = CAST('"
                + baselineRevision + "' AS uuid) AND status = 'PUBLISHED'"));
        assertEquals("0", chunkCount(kbId, second));
        assertTrue(Integer.parseInt(chunkCount(kbId, first)) > 0, "the old chunks remain retrievable");
    }

    @Test
    @DisplayName("deleting the knowledge base during the build cancels everything without resurrecting data")
    void knowledgeBaseDeletedDuringBuildPublishesNothing() throws Exception {
        String userId = insertUser(true);
        String kbId = insertKnowledgeBase(userId);
        String documentId = insertIngestibleDocument(kbId, "库删除.md");
        String jobId = submitIngestibleJob(kbId, documentId, userId);
        embedHook.set(text -> jdbc.update("DELETE FROM knowledge_base WHERE id = CAST(? AS uuid)", kbId));

        worker.runOne();

        assertEquals("0", count("SELECT count(*) FROM exobrain_ingestion_job WHERE id = CAST('" + jobId
                + "' AS uuid)"));
        assertEquals("0", count("SELECT count(*) FROM knowledge_base WHERE id = CAST('" + kbId + "' AS uuid)"));
        assertEquals("0", count("SELECT count(*) FROM exobrain_index_revision WHERE kb_id = CAST('" + kbId
                + "' AS uuid)"));
        assertEquals("0", count("SELECT count(*) FROM chunk_bge_m3 WHERE kb_id = CAST('" + kbId + "' AS uuid)"));
        assertFalse(worker.runOne(), "nothing may be claimed after the resource is gone");
    }

    @Test
    @DisplayName("a disabled account cancels the job before any embedding call")
    void disabledAccountCancelsWithoutEmbedding() throws Exception {
        String userId = insertUser(false);
        String kbId = insertKnowledgeBase(userId);
        String documentId = insertIngestibleDocument(kbId, "停用账号.md");
        String jobId = submitIngestibleJob(kbId, documentId, userId);

        assertTrue(worker.runOne());

        assertEquals("CANCELLED", jobStatus(jobId));
        assertEquals("ACCOUNT_DISABLED", jobErrorCode(jobId));
        assertEquals("0", chunkCount(kbId, documentId));
        assertNull(activeRevision(kbId));
    }

    @Test
    @DisplayName("a disabled embedding model fails permanently without publishing")
    void disabledModelFailsWithoutPublishing() throws Exception {
        String userId = insertUser(true);
        String kbId = insertKnowledgeBase(userId);
        String documentId = insertIngestibleDocument(kbId, "模型停用.md");
        String jobId = submitIngestibleJob(kbId, documentId, userId);
        when(catalog.embeddingForKnowledgeBase(anyString()))
                .thenThrow(new ModelException(409, "此知识库使用的向量模型已停用，请联系管理员"));

        assertTrue(worker.runOne());

        assertEquals("FAILED", jobStatus(jobId));
        assertEquals("MODEL_UNAVAILABLE", jobErrorCode(jobId));
        assertEquals("0", chunkCount(kbId, documentId));
        assertNull(activeRevision(kbId));
    }

    @Test
    @DisplayName("repeated retry clicks keep one active job and publish one set of chunks")
    void duplicateRetryClicksKeepSingleJob() throws Exception {
        String userId = insertUser(true);
        String kbId = insertKnowledgeBase(userId);
        String documentId = insertIngestibleDocument(kbId, "重复重试.md");
        String jobId = submitIngestibleJob(kbId, documentId, userId);
        embedFailure.set(new ModelException(400, "向量查询内容不能为空"));
        assertTrue(worker.runOne());
        assertEquals("FAILED", jobStatus(jobId));

        int clickers = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(clickers);
        List<Future<IngestionJobView>> results = new ArrayList<>();
        try {
            for (int index = 0; index < clickers; index++) {
                Callable<IngestionJobView> click = () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return ingestion.retry(documentId);
                };
                results.add(pool.submit(click));
            }
            start.countDown();
            for (Future<IngestionJobView> future : results) {
                assertEquals(jobId, future.get(15, TimeUnit.SECONDS).jobId(),
                        "every click must resolve to the same job");
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals("1", count("SELECT count(*) FROM exobrain_ingestion_job WHERE document_id = CAST('"
                + documentId + "' AS uuid)"), "no parallel duplicate jobs");
        assertEquals("1", count("SELECT count(*) FROM exobrain_ingestion_job WHERE document_id = CAST('"
                + documentId + "' AS uuid) AND status IN ('QUEUED','RUNNING','RETRY_WAIT')"));

        embedFailure.set(null);
        assertTrue(worker.runOne());
        assertEquals("SUCCEEDED", jobStatus(jobId));
        assertEquals(count("SELECT chunks_total FROM exobrain_ingestion_job WHERE id = CAST('" + jobId
                + "' AS uuid)"), chunkCount(kbId, documentId), "no duplicated chunks after a retry");
    }

    @Test
    @DisplayName("a publish rejected by a changed document set keeps the old index and stays retryable")
    void publishRejectedKeepsOldIndex() throws Exception {
        String userId = insertUser(true);
        String kbId = insertKnowledgeBase(userId);
        String first = insertIngestibleDocument(kbId, "基线.md");
        submitIngestibleJob(kbId, first, userId);
        assertTrue(worker.runOne());
        String baselineRevision = activeRevision(kbId);
        assertNotNull(baselineRevision);

        String second = insertIngestibleDocument(kbId, "候选过期.md");
        String secondJob = submitIngestibleJob(kbId, second, userId);
        String intruder = UUID.randomUUID().toString();
        // 有界故障注入：切块之后插入另一份文档，让候选的文档清单与线上不再一致。
        embedHook.set(text -> jdbc.update("INSERT INTO document (id, kb_id, filename, filetype, size) VALUES "
                + "(CAST(? AS uuid), CAST(? AS uuid), '并发上传.md', 'md', 1)", intruder, kbId));

        worker.runOne();

        assertEquals("RETRY_WAIT", jobStatus(secondJob));
        assertEquals("INDEX_CONFLICT", jobErrorCode(secondJob));
        assertEquals(baselineRevision, activeRevision(kbId), "the old index stays published");
        assertEquals("1", count("SELECT count(*) FROM exobrain_index_revision WHERE kb_id = CAST('" + kbId
                + "' AS uuid) AND status = 'PUBLISHED'"));
        assertEquals("0", chunkCount(kbId, second));

        // 恢复：移除第三方文档后重试同一任务即可成功。
        jdbc.update("DELETE FROM document WHERE id = CAST(? AS uuid)", intruder);
        forceDue(secondJob);
        assertTrue(worker.runOne());
        assertEquals("SUCCEEDED", jobStatus(secondJob));
        assertTrue(Integer.parseInt(chunkCount(kbId, second)) > 0);
    }

    @Test
    @DisplayName("the status view exposes only safe fields and real chunk counts")
    void statusViewIsSafe() throws Exception {
        String userId = insertUser(true);
        String kbId = insertKnowledgeBase(userId);
        String documentId = insertIngestibleDocument(kbId, "状态.md");
        String jobId = submitIngestibleJob(kbId, documentId, userId);
        assertTrue(worker.runOne());

        IngestionJobView view = ingestion.status(documentId).orElseThrow();
        assertEquals(jobId, view.jobId());
        assertEquals("SUCCEEDED", view.status());
        assertEquals(view.chunksTotal(), view.chunksDone());
        assertNull(view.errorCode());
        assertFalse(view.retryable());
        assertFalse(view.toString().contains(storageRoot.toString()),
                "the view must not leak the storage path");
    }

    @Test
    @DisplayName("the orphan sweeper removes a crashed upload but keeps a stored document's file")
    void orphanSweeperRemovesOnlyCrashedUploads() throws Exception {
        String userId = insertUser(true);
        String kbId = insertKnowledgeBase(userId);
        String kept = insertIngestibleDocument(kbId, "保留.md");

        String crashed = UUID.randomUUID().toString();
        Path crashedDirectory = storageRoot.resolve(kbId).resolve(crashed);
        Files.createDirectories(crashedDirectory);
        Files.writeString(crashedDirectory.resolve("crashed.md"), "# crashed\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(crashedDirectory, FileTime.from(Instant.now().minus(3, ChronoUnit.HOURS)));

        var sweeper = new com.kama.jchatmind.ingestion.IngestionOrphanFileSweeper(jobs, settings);
        org.springframework.test.util.ReflectionTestUtils.setField(sweeper, "baseStoragePath",
                storageRoot.toString());

        assertEquals(1, sweeper.sweepOnce());
        assertFalse(Files.exists(crashedDirectory));
        assertTrue(Files.exists(storageRoot.resolve(kbId).resolve(kept).resolve("doc.md")),
                "a stored document's file must never be treated as an orphan");
    }
}
