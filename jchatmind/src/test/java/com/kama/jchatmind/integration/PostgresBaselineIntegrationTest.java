package com.kama.jchatmind.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 PostgreSQL 17 + pgvector 集成检查（对应任务 08 的“真实 PostgreSQL 集成”分类）。
 *
 * <p>数据库来自 {@code EXOBRAIN_IT_JDBC_URL} / {@code -Dexobrain.it.jdbcUrl}（以及
 * {@code EXOBRAIN_IT_USERNAME} / {@code EXOBRAIN_IT_PASSWORD}）。CI 使用 pgvector 服务容器；
 * 本地可以指向一次性容器或开发库。测试会在该实例上创建并删掉一个一次性数据库（{@code exobrain_it_<随机>}），
 * 在其中安装基线脚本与全部迁移，因此不会修改部署库里既有对象；缺少 CREATE DATABASE 权限时如实跳过。
 *
 * <p>未配置数据库或数据库不可达时，除非 {@code EXOBRAIN_IT_REQUIRE_DATABASE=true}（CI 使用，
 * 缺失即失败），否则整个类以 assumption 跳过并在 surefire 报告里如实显示为 skipped。</p>
 */
@Tag("postgres-it")
@DisplayName("Real PostgreSQL 17 + pgvector integration")
class PostgresBaselineIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    /**
     * The deployment migrations create their objects in the connection's default schema, and one
     * constraint is guarded by a catalog-wide name check. Real isolation is therefore an isolated
     * database with its own {@code public} schema, not an extra schema inside a shared database.
     */
    private static final String SCHEMA = "public";
    private static final String DATABASE = "exobrain_it_" + SUFFIX;
    private static final int DIMENSIONS = 1024;

    private static String adminJdbcUrl;
    private static String jdbcUrl;
    private static String databaseUser;
    private static String databasePassword;
    private static boolean requireDatabase;
    private static String skipReason;
    private static Connection admin;
    private static Connection owner;
    private static final List<String> createdRoles = new ArrayList<>();

    @BeforeAll
    static void prepareDatabase() throws Exception {
        adminJdbcUrl = setting("exobrain.it.jdbcUrl", "EXOBRAIN_IT_JDBC_URL");
        databaseUser = setting("exobrain.it.username", "EXOBRAIN_IT_USERNAME");
        databasePassword = setting("exobrain.it.password", "EXOBRAIN_IT_PASSWORD");
        requireDatabase = Boolean.parseBoolean(
                setting("exobrain.it.requireDatabase", "EXOBRAIN_IT_REQUIRE_DATABASE"));

        if (isBlank(adminJdbcUrl)) {
            if (requireDatabase) {
                fail("EXOBRAIN_IT_JDBC_URL (or -Dexobrain.it.jdbcUrl) is required by "
                        + "EXOBRAIN_IT_REQUIRE_DATABASE=true, but no PostgreSQL is configured");
            }
            skipReason = "No PostgreSQL configured (EXOBRAIN_IT_JDBC_URL / -Dexobrain.it.jdbcUrl): "
                    + "real-PostgreSQL integration checks were NOT executed on this host";
            return;
        }

        try {
            admin = DriverManager.getConnection(adminJdbcUrl, databaseUser, databasePassword);
        } catch (SQLException error) {
            if (requireDatabase) {
                throw new IllegalStateException("PostgreSQL is required but not reachable at " + adminJdbcUrl, error);
            }
            skipReason = "PostgreSQL not reachable at " + adminJdbcUrl + " (" + error.getMessage()
                    + "): real-PostgreSQL integration checks were NOT executed on this host";
            return;
        }

        if (!canCreateDatabase()) {
            String reason = "the configured role may not CREATE DATABASE, so the isolated verification "
                    + "database required by these checks could not be created at " + adminJdbcUrl;
            if (requireDatabase) {
                throw new IllegalStateException(reason);
            }
            skipReason = reason + ": real-PostgreSQL integration checks were NOT executed on this host";
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
        for (Path script : baselineScripts()) {
            executeScript(owner, script);
        }
        assertEquals(DATABASE, scalar(owner, "SELECT current_database()"),
                "the checks must run against the throwaway database, not the deployment database");
        System.out.println("[postgres-it] installed " + baselineScripts().size() + " scripts into isolated database "
                + DATABASE + " on server " + scalar(owner, "SHOW server_version"));
    }

    /** The verification database is created from scratch, so the role has to be allowed to create one. */
    private static boolean canCreateDatabase() throws SQLException {
        return Boolean.parseBoolean(scalar(admin,
                "SELECT CASE WHEN rolsuper OR rolcreatedb THEN 'true' ELSE 'false' END FROM pg_roles "
                        + "WHERE rolname = current_user"));
    }

    /**
     * Databases are optional locally but never silently absent: every check below reports as
     * {@code skipped} with the reason, and {@code EXOBRAIN_IT_REQUIRE_DATABASE=true} (CI) turns a
     * missing or unreachable database into a failure before this point.
     */
    @BeforeEach
    void requireConfiguredDatabase() {
        assumeTrue(skipReason == null, skipReason);
    }

    @AfterAll
    static void dropDatabaseObjects() {
        if (owner != null) {
            try (Statement statement = owner.createStatement()) {
                for (String role : createdRoles) {
                    statement.execute("REVOKE ALL ON SCHEMA public FROM " + role);
                    statement.execute("DROP OWNED BY " + role);
                    statement.execute("DROP ROLE " + role);
                }
                createdRoles.clear();
            } catch (SQLException error) {
                System.out.println("[postgres-it] cleanup warning: " + error.getMessage());
            } finally {
                try {
                    owner.close();
                } catch (SQLException ignored) {
                    // closing the scratch connection must not fail the suite
                }
            }
        }
        if (admin != null) {
            try (Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
                System.out.println("[postgres-it] dropped isolated database " + DATABASE);
            } catch (SQLException error) {
                System.out.println("[postgres-it] database cleanup warning: " + error.getMessage());
            } finally {
                try {
                    admin.close();
                } catch (SQLException ignored) {
                    // closing the admin connection must not fail the suite
                }
            }
        }
    }

    @Test
    @DisplayName("baseline schema installs the pgvector column and the L2 operator index")
    void baselineSchemaInstallsVectorColumnAndL2Index() throws SQLException {
        assertTrue(serverMajorVersion() >= 13, "gen_random_uuid() requires PostgreSQL 13 or newer");
        assertEquals("vector(1024)", scalar(owner,
                "SELECT format_type(atttypid, atttypmod) FROM pg_attribute WHERE attrelid = '"
                        + SCHEMA + ".chunk_bge_m3'::regclass AND attname = 'embedding'"));
        assertNotNull(scalar(owner, "SELECT extversion FROM pg_extension WHERE extname = 'vector'"));

        String indexDefinition = scalar(owner,
                "SELECT pg_get_indexdef('" + SCHEMA + ".idx_chunk_bge_m3_embedding'::regclass)");
        assertTrue(indexDefinition.contains("ivfflat"), indexDefinition);
        assertEquals("vector_l2_ops", scalar(owner, "SELECT opc.opcname FROM pg_index i "
                        + "JOIN pg_opclass opc ON opc.oid = i.indclass[0] WHERE i.indexrelid = '"
                        + SCHEMA + ".idx_chunk_bge_m3_embedding'::regclass"),
                "similaritySearch orders by <->, so the index needs the matching L2 operator class");

        for (String table : List.of("agent", "chat_session", "chat_message", "knowledge_base", "document",
                "chunk_bge_m3", "exobrain_user", "exobrain_login_session", "exobrain_resource_acl",
                "exobrain_model", "exobrain_model_state", "exobrain_kb_embedding",
                "exobrain_storage_cleanup")) {
            assertEquals("1", scalar(owner,
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA
                            + "' AND table_name = '" + table + "'"), "missing table " + table);
        }
        assertEquals("1", scalar(owner,
                "SELECT count(*) FROM information_schema.table_constraints WHERE constraint_schema = '" + SCHEMA
                        + "' AND constraint_name = 'exobrain_agent_model_fk'"));
    }

    @Test
    @DisplayName("account and model migrations can be re-applied without error")
    void accountAndModelMigrationsAreIdempotent() throws SQLException {
        String tablesBefore = tableCount();
        for (Path script : migrationScripts()) {
            executeScript(owner, script);
        }
        assertEquals(tablesBefore, tableCount(), "re-applying migrations must not add or drop tables");
    }

    @Test
    @DisplayName("chunk metadata stays compatible with the mapper's uncast insert")
    void chunkMetadataStaysCompatibleWithMapperInsert() throws SQLException {
        assertEquals("text", scalar(owner,
                "SELECT data_type FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                        + "' AND table_name = 'chunk_bge_m3' AND column_name = 'metadata'"));
        String kbId = insertUuidRow("knowledge_base", "mapper-metadata-it");
        String docId = insertDocument(kbId);
        String chunkId = insertChunk(kbId, docId, "mapper insert shape", literal(1.0f), "{\"section\":\"h1\"}");
        assertEquals("{\"section\":\"h1\"}", scalar(owner,
                "SELECT metadata FROM " + SCHEMA + ".chunk_bge_m3 WHERE id = CAST('" + chunkId + "' AS uuid)"));
    }

    @Test
    @DisplayName("similaritySearch stays inside its knowledge base and orders by L2 distance")
    void similaritySearchStaysInsideItsKnowledgeBase() throws SQLException {
        String scopedKb = insertUuidRow("knowledge_base", "similarity-search-it");
        String otherKb = insertUuidRow("knowledge_base", "similarity-search-other");
        String scopedDoc = insertDocument(scopedKb);
        String otherDoc = insertDocument(otherKb);

        insertChunk(scopedKb, scopedDoc, "far", literal(3.0f), "{}");
        insertChunk(scopedKb, scopedDoc, "near", literal(1.0f), "{}");
        insertChunk(otherKb, otherDoc, "other-kb-exact", literal(1.0f), "{}");

        String sql = "SELECT content FROM chunk_bge_m3 WHERE kb_id = CAST(? AS uuid) "
                + "ORDER BY embedding <-> ?::vector LIMIT ?";
        List<String> snippets = new ArrayList<>();
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setString(1, scopedKb);
            statement.setString(2, literal(1.0f));
            statement.setInt(3, 3);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    snippets.add(rows.getString(1));
                }
            }
        }
        assertEquals(List.of("near", "far"), snippets,
                "the query must keep knowledge-base scope and order by L2 distance");
    }

    @Test
    @DisplayName("deleting a knowledge base leaves no retrievable chunks or documents")
    void knowledgeBaseDeletionLeavesNoRetrievableIndex() throws SQLException {
        String kbId = insertUuidRow("knowledge_base", "deletion-it");
        String docId = insertDocument(kbId);
        insertChunk(kbId, docId, "indexed body", literal(1.0f), "{}");

        try (Statement statement = owner.createStatement()) {
            statement.execute("DELETE FROM knowledge_base WHERE id = CAST('" + kbId + "' AS uuid)");
        }
        assertEquals("0", scalar(owner, "SELECT count(*) FROM " + SCHEMA
                + ".chunk_bge_m3 WHERE kb_id = CAST('" + kbId + "' AS uuid)"));
        assertEquals("0", scalar(owner, "SELECT count(*) FROM " + SCHEMA
                + ".document WHERE id = CAST('" + docId + "' AS uuid)"));
    }

    @Test
    @DisplayName("application role is not a superuser and only holds granted privileges")
    void applicationRoleObeysGrants() throws SQLException {
        assumeTrue(canCreateRole(), "the connecting role cannot create roles; run this check against the CI "
                + "pgvector service container (postgres superuser)");

        String role = "exobrain_app_it_" + SUFFIX;
        String password = "it_" + UUID.randomUUID().toString().replace("-", "");
        String ungrantedTable = "it_ungranted_" + SUFFIX;

        try (Statement statement = owner.createStatement()) {
            statement.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE");
            createdRoles.add(role);
            statement.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + role);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + role);
            // Grants mirror deploy/README.md: the runtime role is never a superuser and gets DML only.
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON knowledge_base, document, chunk_bge_m3, "
                    + "agent, chat_session, chat_message, exobrain_user, exobrain_login_session, "
                    + "exobrain_resource_acl, exobrain_model, exobrain_model_state, exobrain_kb_embedding, "
                    + "exobrain_storage_cleanup TO " + role);
            statement.execute("CREATE TABLE " + ungrantedTable + " (id int PRIMARY KEY)");
        }

        try (Connection application = DriverManager.getConnection(jdbcUrl, role, password)) {
            try (Statement statement = application.createStatement()) {
                assertEquals("false", scalar(statement,
                        "SELECT CASE WHEN rolsuper THEN 'true' ELSE 'false' END FROM pg_roles "
                                + "WHERE rolname = current_user"));

                String kbId = UUID.randomUUID().toString();
                statement.execute("INSERT INTO knowledge_base (id, name) VALUES (CAST('" + kbId
                        + "' AS uuid), 'application-role-it')");
                assertEquals("application-role-it", scalar(statement,
                        "SELECT name FROM knowledge_base WHERE id = CAST('" + kbId + "' AS uuid)"));
                statement.execute("DELETE FROM knowledge_base WHERE id = CAST('" + kbId + "' AS uuid)");

                SQLException denied = assertThrows(SQLException.class,
                        () -> scalar(statement, "SELECT count(*) FROM " + ungrantedTable));
                assertEquals("42501", denied.getSQLState(),
                        "a table outside the grant list must be refused: " + denied.getMessage());
                assertFalse(scalar(statement, "SELECT current_user").isBlank());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<Path> baselineScripts() {
        List<Path> scripts = new ArrayList<>();
        scripts.add(repoFile("db/jchatmind.sql"));
        scripts.addAll(migrationScripts());
        return scripts;
    }

    /**
     * Every additive migration in deployment order. The list is discovered from the repository so a
     * migration added by a later task is exercised here as soon as it exists; the file-name prefix
     * (YYYYMMDD-<task>) is the documented application order.
     */
    private static List<Path> migrationScripts() {
        Path directory = repoDirectory("deploy/migrations");
        try (Stream<Path> entries = Files.list(directory)) {
            List<Path> scripts = entries
                    .filter(Files::isRegularFile)
                    .filter(entry -> entry.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
            if (scripts.isEmpty()) {
                throw new IllegalStateException("no migration scripts found in " + directory);
            }
            return scripts;
        } catch (IOException error) {
            throw new IllegalStateException("cannot list " + directory, error);
        }
    }

    /** Resolves a repository-relative directory when the tests run from the module or the repository root. */
    private static Path repoDirectory(String relative) {
        for (Path candidate : List.of(Path.of("..", relative), Path.of(relative),
                Path.of("..", "..", relative))) {
            if (Files.isDirectory(candidate)) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("cannot find directory " + relative + " from "
                + Path.of("").toAbsolutePath());
    }

    /** Resolves a repository-relative path when the tests run from the module or the repository root. */
    private static Path repoFile(String relative) {
        for (Path candidate : List.of(Path.of("..", relative), Path.of(relative),
                Path.of("..", "..", relative))) {
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("cannot find " + relative + " from "
                + Path.of("").toAbsolutePath());
    }

    private static void executeScript(Connection connection, Path script) {
        String sql;
        try {
            sql = Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("cannot read " + script, error);
        }
        for (String statementText : splitStatements(sql)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementText);
            } catch (SQLException error) {
                throw new IllegalStateException("failed statement in " + script + ": "
                        + statementText.replaceAll("\\s+", " ") + " -> " + error.getMessage(), error);
            }
        }
    }

    /**
     * Splits a psql script into statements. Handles line comments, quoted literals and dollar-quoted
     * blocks (the model migration contains {@code DO $$ ... $$}) without needing a psql client.
     */
    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 0;
        while (index < sql.length()) {
            char character = sql.charAt(index);
            if (character == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                while (index < sql.length() && sql.charAt(index) != '\n') {
                    index++;
                }
            } else if (character == '\'') {
                current.append(character);
                index++;
                while (index < sql.length()) {
                    char quoted = sql.charAt(index);
                    current.append(quoted);
                    index++;
                    if (quoted == '\'') {
                        if (index < sql.length() && sql.charAt(index) == '\'') {
                            current.append('\'');
                            index++;
                        } else {
                            break;
                        }
                    }
                }
            } else if (character == '"') {
                current.append(character);
                index++;
                while (index < sql.length()) {
                    char quoted = sql.charAt(index);
                    current.append(quoted);
                    index++;
                    if (quoted == '"') {
                        break;
                    }
                }
            } else if (character == '$') {
                int closing = sql.indexOf('$', index + 1);
                if (closing > index && sql.substring(index + 1, closing).matches("[A-Za-z_]*")) {
                    String tag = sql.substring(index, closing + 1);
                    int end = sql.indexOf(tag, closing + 1);
                    if (end < 0) {
                        current.append(sql.substring(index));
                        index = sql.length();
                    } else {
                        current.append(sql, index, end + tag.length());
                        index = end + tag.length();
                    }
                } else {
                    current.append(character);
                    index++;
                }
            } else if (character == ';') {
                addStatement(statements, current);
                index++;
            } else {
                current.append(character);
                index++;
            }
        }
        addStatement(statements, current);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String text = current.toString().trim();
        current.setLength(0);
        if (!text.isEmpty()) {
            statements.add(text);
        }
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property);
        if (isBlank(value)) {
            value = System.getenv(environment);
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return scalar(statement, sql);
        }
    }

    private static String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                return null;
            }
            Object value = rows.getObject(1);
            return value == null ? null : value.toString();
        }
    }

    private static String tableCount() throws SQLException {
        return scalar(owner, "SELECT count(*) FROM information_schema.tables WHERE table_schema = '" + SCHEMA + "'");
    }

    private static int serverMajorVersion() throws SQLException {
        return Integer.parseInt(scalar(owner, "SHOW server_version").split("\\.")[0]);
    }

    private static boolean canCreateRole() throws SQLException {
        // PostgreSQL renders booleans as true/false when cast to text; compare explicitly.
        return Boolean.parseBoolean(scalar(owner,
                "SELECT CASE WHEN rolsuper OR rolcreaterole THEN 'true' ELSE 'false' END FROM pg_roles "
                        + "WHERE rolname = current_user"));
    }

    private static String insertUuidRow(String table, String name) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO " + SCHEMA + "." + table + " (id, name) VALUES (CAST('" + id
                    + "' AS uuid), '" + name + "')");
        }
        return id;
    }

    private static String insertDocument(String kbId) throws SQLException {
        String id = UUID.randomUUID().toString();
        try (Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO " + SCHEMA + ".document (id, kb_id, filename, filetype, size) VALUES ("
                    + "CAST('" + id + "' AS uuid), CAST('" + kbId + "' AS uuid), 'source.md', 'md', 10)");
        }
        return id;
    }

    /** Mirrors ChunkBgeM3Mapper.insert: no CAST on metadata, {@code ?::vector} on the embedding. */
    private static String insertChunk(String kbId, String docId, String content, String vector, String metadata)
            throws SQLException {
        String id = UUID.randomUUID().toString();
        String sql = "INSERT INTO " + SCHEMA + ".chunk_bge_m3 "
                + "(id, kb_id, doc_id, content, metadata, embedding, created_at, updated_at) VALUES "
                + "(CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?::vector, NOW(), NOW())";
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, kbId);
            statement.setString(3, docId);
            statement.setString(4, content);
            statement.setString(5, metadata);
            statement.setString(6, vector);
            statement.executeUpdate();
        }
        return id;
    }

    /** Deterministic 1024-dimension vector; the L2 distance to the unit query vector grows with the seed. */
    private static String literal(float seed) {
        StringBuilder vector = new StringBuilder("[");
        for (int dimension = 0; dimension < DIMENSIONS; dimension++) {
            if (dimension > 0) {
                vector.append(',');
            }
            vector.append(dimension == 0 ? seed : 0.0f);
        }
        return vector.append(']').toString();
    }
}
