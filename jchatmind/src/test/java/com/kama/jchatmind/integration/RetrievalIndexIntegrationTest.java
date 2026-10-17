package com.kama.jchatmind.integration;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.models.ModelDefinition;
import com.kama.jchatmind.models.ModelKind;
import com.kama.jchatmind.models.ModelRuntimeService;
import com.kama.jchatmind.models.ModelSecretCipher;
import com.kama.jchatmind.models.ModelSettings;
import com.kama.jchatmind.retrieval.ChunkDraft;
import com.kama.jchatmind.retrieval.ChunkMetadata;
import com.kama.jchatmind.retrieval.IndexCandidate;
import com.kama.jchatmind.retrieval.IndexPublisher;
import com.kama.jchatmind.retrieval.IndexScope;
import com.kama.jchatmind.retrieval.IndexVersion;
import com.kama.jchatmind.retrieval.IndexVersionPolicy;
import com.kama.jchatmind.retrieval.PublishedIndex;
import com.kama.jchatmind.retrieval.RetrievalAccessDeniedException;
import com.kama.jchatmind.retrieval.RetrievalEnvelope;
import com.kama.jchatmind.retrieval.RetrievalErrorCode;
import com.kama.jchatmind.retrieval.RetrievalHit;
import com.kama.jchatmind.retrieval.RetrievalIndexRepository;
import com.kama.jchatmind.retrieval.RetrievalIndexService;
import com.kama.jchatmind.retrieval.RetrievalIndexServiceImpl;
import com.kama.jchatmind.retrieval.RetrievalService;
import com.kama.jchatmind.retrieval.RetrievalServiceImpl;
import com.kama.jchatmind.retrieval.RetrievalSettings;
import com.kama.jchatmind.retrieval.ScoredChunk;
import com.kama.jchatmind.retrieval.SourceDocument;
import com.kama.jchatmind.retrieval.SourceText;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import javax.sql.DataSource;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务 03 的真实 PostgreSQL 17 + pgvector 集成检查。
 *
 * <p>沿用 {@link PostgresBaselineIntegrationTest} 的隔离方式：在配置的实例上创建一次性数据库
 * {@code exobrain_it_<随机>}，安装 {@code db/jchatmind.sql} 与全部迁移（含
 * {@code deploy/migrations/20261017-03-body-index.sql}），跑完删除，不修改部署库。
 * 未配置数据库时整体按 assumption 跳过；{@code EXOBRAIN_IT_REQUIRE_DATABASE=true} 时缺失即失败。</p>
 *
 * <p>本类里的向量由确定性桩生成（同一文本 → 同一向量），只验证结构、过滤、原子性与回滚；
 * 效果类断言在 {@link #realEmbeddingAnswersBodyOnlyFacts()} 里用真实 Embedding 端点执行，
 * 没有密钥时如实跳过。两者不能互相替代。</p>
 */
@Tag("postgres-it")
@DisplayName("Real PostgreSQL retrieval index integration (03)")
class RetrievalIndexIntegrationTest {

    private static final int DIMENSIONS = 1024;
    private static final String MODEL_ID = "it-embedding";
    private static final String SCHEMA = "public";
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String DATABASE = "exobrain_it_" + SUFFIX;

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
    private RetrievalIndexRepository repository;
    private IndexPublisher publisher;
    private RetrievalIndexService indexService;
    private RetrievalService retrieval;
    private ModelCatalogService catalog;
    private ModelRuntimeService runtime;
    private final RetrievalSettings settings = new RetrievalSettings();

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
            skipReason = "No PostgreSQL configured (EXOBRAIN_IT_JDBC_URL): retrieval index integration checks "
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
            skipReason = "PostgreSQL not reachable at " + adminJdbcUrl + ": retrieval index integration checks "
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
            skipReason = reason + ": retrieval index integration checks were NOT executed on this host";
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
                repoFile("deploy/migrations/20261017-03-body-index.sql"));
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
    // ------------------------------------------------------------------ harness

    @BeforeEach
    void wireServices() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(jdbcUrl, databaseUser, databasePassword);
        jdbc = new JdbcTemplate(source);
        PlatformTransactionManager manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        chunks = myBatisMapper(source);
        repository = new RetrievalIndexRepository(jdbc);
        catalog = mock(ModelCatalogService.class);
        when(catalog.embeddingForKnowledgeBase(anyString())).thenReturn(embeddingModel());
        runtime = mock(ModelRuntimeService.class);
        when(runtime.embed(org.mockito.ArgumentMatchers.any(), anyString()))
                .thenAnswer(invocation -> stubVector(invocation.getArgument(1, String.class)));
        publisher = transactional(new IndexPublisher(repository, chunks, catalog), manager);
        indexService = new RetrievalIndexServiceImpl(repository, publisher, catalog, runtime, settings);
        retrieval = new RetrievalServiceImpl(chunks, catalog, runtime, indexService, repository, settings);
        ensureEmbeddingModelRow();
    }

    private static ChunkBgeM3Mapper myBatisMapper(DataSource source) throws IOException {
        Configuration configuration = new Configuration(new Environment("retrieval-it",
                new SpringManagedTransactionFactory(), source));
        // 与 application.yaml 的 mybatis.type-handlers-package 保持一致，否则 embedding 列没有 TypeHandler。
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

    private String insertKnowledgeBase(String name) {
        String kbId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO knowledge_base (id, name) VALUES (CAST(? AS uuid), ?)", kbId, name + SUFFIX);
        jdbc.update("INSERT INTO exobrain_kb_embedding (kb_id, model_id) VALUES (CAST(? AS uuid), ?)", kbId, MODEL_ID);
        return kbId;
    }

    private String insertDocument(String kbId, String filename) {
        String documentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO document (id, kb_id, filename, filetype, size) VALUES (CAST(? AS uuid), "
                + "CAST(? AS uuid), ?, 'md', 10)", documentId, kbId, filename);
        return documentId;
    }

    /** 确定性桩向量：同一文本总是得到同一向量，因此可验证路由与过滤，但不代表语义效果。 */
    private static float[] stubVector(String text) {
        float[] vector = new float[DIMENSIONS];
        vector[0] = 1.0f;
        vector[Math.abs(text.hashCode()) % (DIMENSIONS - 1) + 1] = 0.01f;
        return vector;
    }

    private static float[] seedVector(float seed) {
        float[] vector = new float[DIMENSIONS];
        vector[0] = seed;
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

    /** 返回单列查询结果的第一行（计数或 uuid 都能用），没有行时返回 null。 */
    private String count(String sql) {
        List<String> values = jdbc.query(sql, (rs, row) -> rs.getString(1));
        return values.isEmpty() ? null : values.get(0);
    }

    private static SourceDocument source(String documentId, String name, String markdown) {
        return new SourceDocument(documentId, name, SourceText.normalize(markdown.getBytes(StandardCharsets.UTF_8)));
    }

    private IndexCandidate embedded(IndexCandidate candidate) {
        List<float[]> vectors = new ArrayList<>();
        for (ChunkDraft chunk : candidate.chunks()) {
            vectors.add(stubVector(chunk.embeddingText()));
        }
        return candidate.withEmbeddings(vectors);
    }

    private void insertLegacyChunk(String kbId, String documentId, String content, float seed) {
        jdbc.update("INSERT INTO chunk_bge_m3 (kb_id, doc_id, content, metadata, embedding, created_at, updated_at) "
                        + "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, NULL, CAST(? AS vector), NOW(), NOW())",
                kbId, documentId, content, literal(seedVector(seed)));
    }

    private static IndexVersion versionOf(ScoredChunk row) {
        return IndexVersion.fromWire(row.getIndexVersion());
    }
    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("the migration adds the versioned tables, the chunk columns and the revision indexes")
    void migrationAddsVersionedStructures() {
        for (String table : List.of("exobrain_index_revision", "exobrain_kb_index_state")) {
            assertEquals("1", count("SELECT count(*) FROM information_schema.tables WHERE table_schema = '"
                    + SCHEMA + "' AND table_name = '" + table + "'"), "missing table " + table);
        }
        for (String column : List.of("revision_id", "index_version", "chunk_ordinal", "source_hash", "start_line",
                "end_line")) {
            assertEquals("1", count("SELECT count(*) FROM information_schema.columns WHERE table_schema = '"
                    + SCHEMA + "' AND table_name = 'chunk_bge_m3' AND column_name = '" + column + "'"),
                    "missing column chunk_bge_m3." + column);
        }
        assertEquals("1", count("SELECT count(*) FROM pg_indexes WHERE schemaname = '" + SCHEMA
                + "' AND indexname = 'idx_chunk_bge_m3_revision'"));
        assertEquals("1", count("SELECT count(*) FROM pg_indexes WHERE schemaname = '" + SCHEMA
                + "' AND indexname = 'exobrain_index_revision_active'"));
    }

    @Test
    @DisplayName("re-applying every migration keeps the schema and leaves pre-existing legacy rows readable")
    void migrationIsIdempotentAndKeepsLegacyRowsReadable() {
        String kbId = insertKnowledgeBase("legacy-it");
        String documentId = insertDocument(kbId, "legacy.md");
        insertLegacyChunk(kbId, documentId, "旧的标题向量片段", 1.0f);
        // 基线脚本的 agent 种子数据有到 exobrain_model 的外键；模型目录平时由应用启动导入，这里补一行让整组脚本可重放。
        jdbc.update("INSERT INTO exobrain_model (id, name, kind, protocol, base_url, provider_model, enabled, "
                + "default_model, dimensions) VALUES ('deepseek-chat', 'DeepSeek Chat', 'LLM', 'deepseek', "
                + "'https://api.deepseek.com', 'deepseek-chat', TRUE, FALSE, NULL) ON CONFLICT (id) DO NOTHING");

        for (Path script : installScripts()) {
            executeScript(owner, script);
        }

        assertEquals("1", count("SELECT count(*) FROM chunk_bge_m3 WHERE kb_id = CAST('" + kbId + "' AS uuid)"));
        IndexScope scope = indexService.resolveScope(kbId);
        assertEquals(IndexVersion.LEGACY_TITLE_V1, scope.indexVersion());
        assertEquals(IndexScope.LEGACY_REVISION, scope.indexRevision());
        assertNull(scalar(owner, "SELECT start_line FROM chunk_bge_m3 WHERE kb_id = CAST('" + kbId
                + "' AS uuid)"), "legacy rows must keep NULL positions");
        assertNull(scalar(owner, "SELECT end_line FROM chunk_bge_m3 WHERE kb_id = CAST('" + kbId + "' AS uuid)"));
        assertNull(scalar(owner, "SELECT source_hash FROM chunk_bge_m3 WHERE kb_id = CAST('" + kbId + "' AS uuid)"));
        assertNull(scalar(owner, "SELECT index_version FROM chunk_bge_m3 WHERE kb_id = CAST('" + kbId
                + "' AS uuid)"), "pre-existing rows stay untouched");
    }

    @Test
    @DisplayName("metadata round-trips through the TEXT column and 1024 dimensions are enforced")
    void metadataRoundTripsAndDimensionsAreEnforced() {
        String kbId = insertKnowledgeBase("metadata-it");
        String documentId = insertDocument(kbId, "设计.md");
        SourceDocument source = source(documentId, "设计.md", "# 部署\n\n正文第一段。\n\n正文第二段。\n");
        IndexCandidate candidate = embedded(indexService.prepareRebuild(kbId, List.of(source)));
        PublishedIndex published = publisher.publish(candidate);

        ScoredChunk row = chunks.searchScoped(kbId, published.scope().revisionId(),
                literal(candidate.embeddings().get(0)), 3).get(0);
        ChunkMetadata metadata = ChunkMetadata.decode(row.getMetadata());
        assertEquals(List.of("部署"), metadata.headingPath());
        assertEquals(3, metadata.startLine());
        assertEquals(5, metadata.endLine());
        assertEquals(source.sourceHash(), metadata.sourceHash());
        assertEquals("body-v1", metadata.indexVersion());
        assertEquals("设计.md", metadata.documentName());
        assertEquals(IndexVersion.BODY_V1, versionOf(row));
        assertEquals(3, row.getStartLine());
        assertEquals(5, row.getEndLine());
        assertEquals(source.sourceHash(), row.getSourceHash());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO chunk_bge_m3 "
                        + "(kb_id, doc_id, content, embedding, created_at, updated_at) VALUES (CAST(? AS uuid), "
                        + "CAST(? AS uuid), '错误维度', CAST(? AS vector), NOW(), NOW())",
                kbId, documentId, literal(new float[1023])));
    }

    @Test
    @DisplayName("a publish keeps the old index when the candidate no longer matches the live documents")
    void failedPublishKeepsTheOldIndex() {
        String kbId = insertKnowledgeBase("publish-failure-it");
        String first = insertDocument(kbId, "one.md");
        PublishedIndex baseline = publisher.publish(embedded(indexService.prepareRebuild(kbId,
                List.of(source(first, "one.md", "# A\n\n第一版正文。\n")))));
        assertNotNull(baseline.revision().id());

        insertDocument(kbId, "two.md");
        IndexCandidate stale = embedded(indexService.prepareRebuild(kbId,
                List.of(source(first, "one.md", "# A\n\n第一版正文。\n"))));

        assertThrows(RuntimeException.class, () -> publisher.publish(stale));

        assertEquals(baseline.revision().id(), count("SELECT active_revision_id FROM exobrain_kb_index_state "
                + "WHERE kb_id = CAST('" + kbId + "' AS uuid)"), "the old published revision must stay active");
        assertEquals("1", count("SELECT count(*) FROM chunk_bge_m3 WHERE revision_id = CAST('"
                + baseline.revision().id() + "' AS uuid)"), "old chunks must not be deleted");
        assertEquals("0", count("SELECT count(*) FROM exobrain_index_revision WHERE kb_id = CAST('" + kbId
                + "' AS uuid) AND status = 'FAILED'"), "a rolled back publish must not leave a fake revision");
    }

    @Test
    @DisplayName("a deleted document blocks publishing, and chunk rows cannot reference a missing document")
    void deletionBlocksPublishing() {
        String kbId = insertKnowledgeBase("deletion-it");
        String documentId = insertDocument(kbId, "gone.md");
        IndexCandidate candidate = embedded(indexService.prepareRebuild(kbId,
                List.of(source(documentId, "gone.md", "# 删除\n\n即将被删除的正文。\n"))));

        jdbc.update("DELETE FROM document WHERE id = CAST(? AS uuid)", documentId);

        assertThrows(RuntimeException.class, () -> publisher.publish(candidate));
        assertEquals("0", count("SELECT count(*) FROM exobrain_index_revision WHERE kb_id = CAST('" + kbId
                + "' AS uuid)"), "no revision may be published after the document disappeared");

        String otherKb = insertKnowledgeBase("fk-it");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO chunk_bge_m3 "
                        + "(kb_id, doc_id, content, embedding, created_at, updated_at) VALUES (CAST(? AS uuid), "
                        + "CAST(? AS uuid), '孤儿 chunk', CAST(? AS vector), NOW(), NOW())",
                otherKb, UUID.randomUUID().toString(), literal(new float[DIMENSIONS])),
                "the foreign key must keep orphan chunks out of the index");
    }

    @Test
    @DisplayName("publishing waits for the knowledge-base lock, so deletion and publishing cannot interleave")
    void publishingWaitsForTheKnowledgeBaseLock() throws Exception {
        String kbId = insertKnowledgeBase("lock-it");
        String documentId = insertDocument(kbId, "lock.md");
        IndexCandidate candidate = embedded(indexService.prepareRebuild(kbId,
                List.of(source(documentId, "lock.md", "# 锁\n\n等待锁的正文。\n"))));

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> transactions.execute(status -> {
            jdbc.queryForObject("SELECT id FROM knowledge_base WHERE id = CAST(? AS uuid) FOR UPDATE",
                    String.class, kbId);
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertTrue(locked.await(30, TimeUnit.SECONDS), "the test must hold the knowledge_base row lock");

        AtomicReference<PublishedIndex> published = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Thread publishing = new Thread(() -> {
            try {
                published.set(publisher.publish(candidate));
            } catch (RuntimeException error) {
                failure.set(error);
            }
        });
        publishing.start();
        publishing.join(1500);
        assertTrue(publishing.isAlive(), "publishing must block while another transaction holds the KB lock");

        release.countDown();
        publishing.join(30_000);
        holder.join(30_000);
        assertNull(failure.get(), () -> "publishing failed: " + failure.get());
        assertNotNull(published.get());
        assertEquals(published.get().revision().id(), count("SELECT active_revision_id FROM exobrain_kb_index_state "
                + "WHERE kb_id = CAST('" + kbId + "' AS uuid)"));
    }

    @Test
    @DisplayName("legacy rows and body revisions never mix inside one knowledge base")
    void legacyAndBodyRowsDoNotMix() {
        String legacyKb = insertKnowledgeBase("mixed-legacy-it");
        String legacyDocument = insertDocument(legacyKb, "old.md");
        insertLegacyChunk(legacyKb, legacyDocument, "legacy-title-v1 正文", 1.0f);
        insertLegacyChunk(legacyKb, legacyDocument, "legacy-title-v1 正文 2", 2.0f);

        SourceDocument source = source(legacyDocument, "old.md", "# 正文\n\n重建后的正文。\n");
        PublishedIndex published = publisher.publish(embedded(indexService.prepareRebuild(legacyKb, List.of(source))));

        List<ScoredChunk> scoped = chunks.searchScoped(legacyKb, published.scope().revisionId(),
                literal(new float[DIMENSIONS]), 10);
        assertEquals(1, scoped.size(), "a body query must only see the published revision");
        assertEquals(IndexVersion.BODY_V1.wire(), scoped.get(0).getIndexVersion());
        assertEquals("2", count("SELECT count(*) FROM chunk_bge_m3 WHERE kb_id = CAST('" + legacyKb
                + "' AS uuid) AND revision_id IS NULL"),
                "legacy rows stay untouched (NULL revision): 重建只是让它们不再被检索，不会删除或回填旧数据");
        assertTrue(scoped.stream().noneMatch(row -> row.getRevisionId() == null));

        List<ScoredChunk> legacyScoped = chunks.searchScoped(legacyKb, null, literal(new float[DIMENSIONS]), 10);
        assertEquals(2, legacyScoped.size(), "the legacy scope still reads the legacy rows");
        assertTrue(legacyScoped.stream().allMatch(row -> row.getRevisionId() == null
                && row.getSourceHash() == null && row.getStartLine() == null));

        String freshKb = insertKnowledgeBase("fresh-body-it");
        assertEquals(IndexVersion.BODY_V1, indexService.resolveScope(freshKb).indexVersion(),
                "an empty KB defaults to body-v1");
        assertEquals(IndexVersion.LEGACY_TITLE_V1, IndexVersionPolicy.resolve(null, true).indexVersion());
    }
    @Test
    @DisplayName("document deletion removes the retrievable rows and refreshes the active revision honestly")
    void documentDeletionLeavesNoRetrievableIndex() {
        String kbId = insertKnowledgeBase("doc-delete-it");
        String keep = insertDocument(kbId, "keep.md");
        String drop = insertDocument(kbId, "drop.md");
        PublishedIndex published = publisher.publish(embedded(indexService.prepareRebuild(kbId,
                List.of(source(keep, "keep.md", "# 保留\n\n保留的正文。\n"),
                        source(drop, "drop.md", "# 删除\n\n要删除的正文。\n")))));
        assertEquals("2", count("SELECT count(*) FROM chunk_bge_m3 WHERE revision_id = CAST('"
                + published.revision().id() + "' AS uuid)"));

        jdbc.update("DELETE FROM document WHERE id = CAST(? AS uuid)", drop);
        indexService.refreshActiveRevision(kbId);

        assertEquals("1", count("SELECT count(*) FROM chunk_bge_m3 WHERE revision_id = CAST('"
                + published.revision().id() + "' AS uuid)"), "the deleted document must leave no chunk");
        assertEquals("1", count("SELECT document_count FROM exobrain_index_revision WHERE id = CAST('"
                + published.revision().id() + "' AS uuid)"), "counts must be recomputed after deletion");
        assertNull(scalar(owner, "SELECT source_set_hash FROM exobrain_index_revision WHERE id = CAST('"
                        + published.revision().id() + "' AS uuid)"),
                "a stale source-set hash must be cleared instead of pretending the set is unchanged");
        RetrievalEnvelope envelope = retrieval.retrieve(kbId, "要删除的正文", ignored -> true);
        assertEquals("OK", envelope.status().name());
        assertTrue(envelope.hits().stream().noneMatch(hit -> hit.content().contains("要删除的正文")));
    }

    @Test
    @DisplayName("rollback reactivates an existing revision and refuses revisions whose data was cleaned")
    void rollbackRefusesCleanedRevisions() {
        String kbId = insertKnowledgeBase("rollback-it");
        String documentId = insertDocument(kbId, "rollback.md");
        PublishedIndex first = publisher.publish(embedded(indexService.prepareRebuild(kbId,
                List.of(source(documentId, "rollback.md", "# 版本一\n\n第一版正文。\n")))));
        PublishedIndex second = publisher.publish(embedded(indexService.prepareRebuild(kbId,
                List.of(source(documentId, "rollback.md", "# 版本二\n\n第二版正文。\n")))));

        assertEquals("SUPERSEDED", count("SELECT status FROM exobrain_index_revision WHERE id = CAST('"
                + first.revision().id() + "' AS uuid)"));
        PublishedIndex rolledBack = publisher.rollback(kbId, first.revision().id());
        assertEquals(first.revision().id(), rolledBack.revision().id());
        assertEquals(first.revision().id(), count("SELECT active_revision_id FROM exobrain_kb_index_state WHERE "
                + "kb_id = CAST('" + kbId + "' AS uuid)"));
        assertEquals("SUPERSEDED", count("SELECT status FROM exobrain_index_revision WHERE id = CAST('"
                + second.revision().id() + "' AS uuid)"));

        assertEquals(1, publisher.cleanupSuperseded(kbId, 0), "the superseded revision is cleaned");
        assertEquals("0", count("SELECT count(*) FROM chunk_bge_m3 WHERE revision_id = CAST('"
                + second.revision().id() + "' AS uuid)"), "cleaned revisions lose their chunks");
        assertThrows(RuntimeException.class, () -> publisher.rollback(kbId, second.revision().id()));
        assertEquals(first.revision().id(), count("SELECT active_revision_id FROM exobrain_kb_index_state WHERE "
                + "kb_id = CAST('" + kbId + "' AS uuid)"), "the active revision survives cleanup");
    }

    @Test
    @DisplayName("deleting the knowledge base removes revisions, state and chunks")
    void knowledgeBaseDeletionRemovesIndexes() {
        String kbId = insertKnowledgeBase("kb-delete-it");
        String documentId = insertDocument(kbId, "kb.md");
        publisher.publish(embedded(indexService.prepareRebuild(kbId,
                List.of(source(documentId, "kb.md", "# KB\n\n删除知识库的正文。\n")))));

        jdbc.update("DELETE FROM knowledge_base WHERE id = CAST(? AS uuid)", kbId);

        assertEquals("0", count("SELECT count(*) FROM exobrain_index_revision WHERE kb_id = CAST('" + kbId
                + "' AS uuid)"));
        assertEquals("0", count("SELECT count(*) FROM exobrain_kb_index_state WHERE kb_id = CAST('" + kbId
                + "' AS uuid)"));
        assertEquals("0", count("SELECT count(*) FROM chunk_bge_m3 WHERE kb_id = CAST('" + kbId + "' AS uuid)"));
    }

    @Test
    @DisplayName("retrieval filters by knowledge base and active revision and reports statuses honestly")
    void retrievalFiltersByKnowledgeBaseAndRevision() {
        String kbId = insertKnowledgeBase("retrieval-it");
        String documentId = insertDocument(kbId, "设计.md");
        PublishedIndex published = publisher.publish(embedded(indexService.prepareRebuild(kbId,
                List.of(source(documentId, "设计.md", "# 部署\n\n端口是 8080。\n\n探针间隔是 7 秒。\n")))));

        String otherKb = insertKnowledgeBase("retrieval-other-it");
        String otherDocument = insertDocument(otherKb, "其他.md");
        publisher.publish(embedded(indexService.prepareRebuild(otherKb,
                List.of(source(otherDocument, "其他.md", "# 其他\n\n另一份文档的正文。\n")))));

        RetrievalEnvelope envelope = retrieval.retrieve(kbId, "探针间隔是 7 秒", ignored -> true);
        assertEquals("OK", envelope.status().name());
        assertEquals(1, envelope.hits().size());
        RetrievalHit hit = envelope.hits().get(0);
        assertEquals(kbId, hit.kbId());
        assertEquals("设计.md", hit.source().documentName());
        assertEquals(List.of("部署"), hit.source().headingPath());
        // 章节正文从标题行之后开始：第 1 行是标题，第 2 行空行，正文覆盖第 3–5 行。
        assertEquals(3, hit.source().startLine());
        assertEquals(5, hit.source().endLine());
        assertEquals("l2", hit.metric());
        assertTrue(Double.isFinite(hit.distance()) && hit.distance() >= 0);
        assertEquals(published.revision().id(), hit.indexRevision());
        assertEquals("body-v1", hit.indexVersion());
        assertEquals(envelope.retrievalId() + ":1", hit.citationId());
        assertTrue(envelope.hits().stream().noneMatch(candidate -> candidate.kbId().equals(otherKb)));

        settings.setTopK(1);
        assertEquals(1, retrieval.retrieve(kbId, "探针间隔是 7 秒", ignored -> true).hits().size());
        settings.setTopK(3);

        RetrievalEnvelope empty = retrieval.retrieve(UUID.randomUUID().toString(), "查询", ignored -> true);
        assertEquals("NO_MATCH", empty.status().name());
        assertEquals(RetrievalErrorCode.NO_INDEXED_CONTENT, empty.errorCode());
        assertTrue(empty.hits().isEmpty());

        assertThrows(RetrievalAccessDeniedException.class,
                () -> retrieval.retrieve(kbId, "查询", ignored -> false));
    }

    @Test
    @DisplayName("a rebuild keeps the previous revision usable and records a failed attempt")
    void rebuildRecordsFailureAndKeepsPreviousRevision() {
        String kbId = insertKnowledgeBase("rebuild-it");
        String documentId = insertDocument(kbId, "rebuild.md");
        PublishedIndex first = publisher.publish(embedded(indexService.prepareRebuild(kbId,
                List.of(source(documentId, "rebuild.md", "# 重建\n\n第一版正文。\n")))));

        when(runtime.embed(org.mockito.ArgumentMatchers.any(), anyString()))
                .thenThrow(new IllegalStateException("embedding provider down"));
        assertThrows(RuntimeException.class, () -> indexService.rebuild(kbId,
                List.of(source(documentId, "rebuild.md", "# 重建\n\n第二版正文。\n"))));

        assertEquals("1", count("SELECT count(*) FROM exobrain_index_revision WHERE kb_id = CAST('" + kbId
                + "' AS uuid) AND status = 'FAILED'"), "the failed attempt is recorded");
        assertEquals(first.revision().id(), count("SELECT active_revision_id FROM exobrain_kb_index_state WHERE "
                + "kb_id = CAST('" + kbId + "' AS uuid)"), "the previous index stays usable");
        assertEquals("1", count("SELECT count(*) FROM chunk_bge_m3 WHERE revision_id = CAST('"
                + first.revision().id() + "' AS uuid)"));

        doAnswer(invocation -> stubVector(invocation.getArgument(1, String.class)))
                .when(runtime).embed(org.mockito.ArgumentMatchers.any(), anyString());
        PublishedIndex second = indexService.rebuild(kbId,
                List.of(source(documentId, "rebuild.md", "# 重建\n\n第二版正文。\n")));
        assertEquals("SUPERSEDED", count("SELECT status FROM exobrain_index_revision WHERE id = CAST('"
                + first.revision().id() + "' AS uuid)"));
        assertEquals(second.revision().id(), count("SELECT active_revision_id FROM exobrain_kb_index_state WHERE "
                + "kb_id = CAST('" + kbId + "' AS uuid)"));
    }

    @Test
    @DisplayName("real embeddings answer body-only, duplicate-heading and long-section-tail facts")
    void realEmbeddingAnswersBodyOnlyFacts() throws Exception {
        String apiKey = realEmbeddingKey();
        assumeTrue(apiKey != null, "EXOBRAIN_REAL_EMBEDDING_API_KEY (or app.embedding.api-key in the untracked "
                + "application-dev.yaml) is not available: real-embedding effectiveness checks were NOT executed");

        // 密钥只在内存与一次性数据库里使用：加密密钥文件写在系统临时目录，绝不进入仓库。
        byte[] randomKey = new byte[32];
        new java.security.SecureRandom().nextBytes(randomKey);
        Path keyFile = Files.createTempFile("exobrain-it-model-key", ".txt");
        Files.writeString(keyFile, java.util.Base64.getEncoder().encodeToString(randomKey),
                StandardCharsets.US_ASCII);
        ModelSettings modelSettings = new ModelSettings();
        modelSettings.setEncryptionKeyFile(keyFile.toString());
        ModelSecretCipher cipher = new ModelSecretCipher(modelSettings);

        ModelDefinition realModel = new ModelDefinition("it-real-embedding", "真实测试向量模型",
                ModelKind.EMBEDDING, "openai", realEmbeddingBaseUrl(), "BAAI/bge-m3",
                cipher.encrypt("it-real-embedding", apiKey),
                true, true, DIMENSIONS, Instant.now(), Instant.now());
        jdbc.update("INSERT INTO exobrain_model (id, name, kind, protocol, base_url, provider_model, "
                        + "encrypted_api_key, enabled, default_model, dimensions) VALUES (?, ?, 'EMBEDDING', "
                        + "'openai', ?, 'BAAI/bge-m3', ?, TRUE, FALSE, ?) ON CONFLICT (id) DO UPDATE SET "
                        + "provider_model = EXCLUDED.provider_model, base_url = EXCLUDED.base_url, "
                        + "encrypted_api_key = EXCLUDED.encrypted_api_key",
                realModel.id(), realModel.name(), realModel.baseUrl(), realModel.encryptedApiKey(), DIMENSIONS);
        ModelRuntimeService realRuntime = new ModelRuntimeService(cipher, modelSettings, WebClient.builder());
        when(catalog.embeddingForKnowledgeBase(anyString())).thenReturn(realModel);
        RetrievalIndexServiceImpl realIndex = new RetrievalIndexServiceImpl(repository, publisher, catalog,
                realRuntime, settings);
        RetrievalService realRetrieval = new RetrievalServiceImpl(chunks, catalog, realRuntime, realIndex,
                repository, settings);

        String kbId = insertKnowledgeBase("real-facts-it");
        jdbc.update("INSERT INTO exobrain_kb_embedding (kb_id, model_id) VALUES (CAST(? AS uuid), ?) "
                + "ON CONFLICT (kb_id) DO UPDATE SET model_id = EXCLUDED.model_id", kbId, realModel.id());

        List<SourceDocument> documents = new ArrayList<>();
        for (String file : List.of("bom-crlf.md", "chinese.md", "duplicate-headings.md", "long-section.md",
                "table.md", "code-block.md")) {
            String documentId = insertDocument(kbId, file);
            documents.add(new SourceDocument(documentId, file, SourceText.normalize(goldenBytes(file))));
        }
        PublishedIndex published = realIndex.rebuild(kbId, documents);
        System.out.println("[postgres-it] real embedding published revision " + published.revision().id()
                + " with " + published.chunkCount() + " chunks from " + documents.size() + " documents");

        assertFact(realRetrieval, kbId, documents, "就绪探针间隔是多少秒", "bom-crlf.md", "BOM-CRLF-FACT", 7);
        assertFact(realRetrieval, kbId, documents, "中文资料的保存期限是多少天", "chinese.md", "CN-FACT", 7);
        assertFact(realRetrieval, kbId, documents, "A 组并发上限是多少", "duplicate-headings.md", "DUP-A", 5);
        assertFact(realRetrieval, kbId, documents, "长章节尾部校验码 T-121 是什么", "long-section.md",
                "LONG-TAIL-FACT", 126);
    }

    private void assertFact(RetrievalService service, String kbId, List<SourceDocument> documents, String query,
                            String expectedFile, String marker, int markerLine) {
        RetrievalEnvelope envelope = service.retrieve(kbId, query, ignored -> true);
        System.out.println("[postgres-it] real embedding query '" + query + "' -> " + envelope.status()
                + " hits=" + envelope.hits().stream()
                        .map(hit -> hit.source().documentName() + "@" + hit.source().startLine() + "-"
                                + hit.source().endLine() + " d=" + String.format("%.4f", hit.distance()))
                        .toList());
        assertEquals("OK", envelope.status().name(), "the query must return a hit: " + query);
        String expectedDocumentId = documents.stream()
                .filter(document -> document.documentName().equals(expectedFile)).findFirst()
                .orElseThrow().documentId();
        boolean matched = envelope.hits().stream().anyMatch(hit ->
                hit.source().documentId().equals(expectedDocumentId)
                        && hit.content().contains(marker)
                        && hit.source().startLine() != null && hit.source().startLine() <= markerLine
                        && hit.source().endLine() != null && markerLine <= hit.source().endLine());
        assertTrue(matched, () -> "expected " + marker + " from " + expectedFile + " covering line " + markerLine
                + ", got " + envelope.hits().stream().map(hit -> hit.source().documentName() + ":"
                        + hit.source().startLine() + "-" + hit.source().endLine()).toList());
    }

    private static byte[] goldenBytes(String file) throws IOException {
        try (var stream = RetrievalIndexIntegrationTest.class.getClassLoader()
                .getResourceAsStream("retrieval-golden/" + file)) {
            if (stream == null) {
                throw new IllegalStateException("missing golden fixture " + file);
            }
            return stream.readAllBytes();
        }
    }

    /** 真实端点与密钥只在内存里使用，绝不写入仓库、命令行或日志。 */
    private static String realEmbeddingKey() {
        String fromEnvironment = System.getenv("EXOBRAIN_REAL_EMBEDDING_API_KEY");
        if (!isBlank(fromEnvironment)) {
            return fromEnvironment.trim();
        }
        for (Path candidate : List.of(Path.of("src/main/resources/application-dev.yaml"),
                Path.of("..", "src/main/resources/application-dev.yaml"),
                Path.of("jchatmind/src/main/resources/application-dev.yaml"))) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                boolean inEmbedding = false;
                for (String line : Files.readAllLines(candidate, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("embedding:")) {
                        inEmbedding = true;
                    } else if (inEmbedding && trimmed.startsWith("api-key:")) {
                        String value = trimmed.substring("api-key:".length()).trim();
                        if (!value.isEmpty()) {
                            return value;
                        }
                    }
                }
            } catch (IOException unreadable) {
                return null;
            }
        }
        return null;
    }

    private static String realEmbeddingBaseUrl() {
        String override = System.getenv("EXOBRAIN_REAL_EMBEDDING_BASE_URL");
        return isBlank(override) ? "https://emrank.tech/v1" : override.trim();
    }
}