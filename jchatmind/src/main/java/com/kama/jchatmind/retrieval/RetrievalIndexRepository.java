package com.kama.jchatmind.retrieval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 索引版本/状态/文档清单的读写（表 {@code exobrain_index_revision}、{@code exobrain_kb_index_state}，
 * 以及 {@code chunk_bge_m3} 的增量列）。迁移见 {@code deploy/migrations/20261017-03-body-index.sql}。
 *
 * <p>这里只做集合级查询与写入；向量本身由 {@code ChunkBgeM3Mapper} 负责。所有 KB 过滤都在 SQL 里完成，
 * 不先查全库再由 Java 过滤。</p>
 */
@Repository
public class RetrievalIndexRepository {

    private static final String REVISION_COLUMNS = "id, kb_id, index_version, status, model_catalog_id, "
            + "model_name, dimensions, document_count, chunk_count, source_set_hash, created_at, published_at, "
            + "superseded_at";

    private static final RowMapper<IndexRevision> REVISION_ROW = (rs, row) -> new IndexRevision(
            rs.getString("id"), rs.getString("kb_id"),
            IndexVersion.fromWire(rs.getString("index_version")),
            IndexRevision.IndexStatus.valueOf(rs.getString("status")),
            rs.getString("model_catalog_id"), rs.getString("model_name"), rs.getInt("dimensions"),
            rs.getInt("document_count"), rs.getInt("chunk_count"), rs.getString("source_set_hash"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toLocalDateTime(),
            rs.getTimestamp("superseded_at") == null ? null : rs.getTimestamp("superseded_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public RetrievalIndexRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<KbIndexState> findState(String kbId) {
        return jdbc.query("SELECT kb_id, active_revision_id, active_index_version, updated_at "
                        + "FROM exobrain_kb_index_state WHERE kb_id = CAST(? AS uuid)", (rs, row) -> new KbIndexState(
                        rs.getString("kb_id"), rs.getString("active_revision_id"),
                        IndexVersion.fromWire(rs.getString("active_index_version")),
                        rs.getTimestamp("updated_at").toLocalDateTime()), kbId)
                .stream().findFirst();
    }

    public Optional<IndexRevision> findRevision(String revisionId) {
        return jdbc.query("SELECT " + REVISION_COLUMNS + " FROM exobrain_index_revision WHERE id = CAST(? AS uuid)",
                REVISION_ROW, revisionId).stream().findFirst();
    }

    public List<IndexRevision> listRevisions(String kbId) {
        return jdbc.query("SELECT " + REVISION_COLUMNS + " FROM exobrain_index_revision WHERE kb_id = CAST(? AS uuid) "
                + "ORDER BY created_at DESC", REVISION_ROW, kbId);
    }

    /** KB 是否已经有 legacy 行（{@code revision_id IS NULL}）。 */
    public boolean hasLegacyChunks(String kbId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM chunk_bge_m3 "
                + "WHERE kb_id = CAST(? AS uuid) AND revision_id IS NULL)", Boolean.class, kbId));
    }

    public boolean knowledgeBaseExists(String kbId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM knowledge_base WHERE id = CAST(? AS uuid))", Boolean.class, kbId));
    }

    /**
     * 序列化同一个 KB 的发布（以及阻止发布期间该 KB 被删除）：锁住 knowledge_base 行本身。
     * 必须在事务内调用。
     */
    public boolean lockKnowledgeBase(String kbId) {
        return !jdbc.query("SELECT id FROM knowledge_base WHERE id = CAST(? AS uuid) FOR UPDATE", (rs, row) -> 1,
                kbId).isEmpty();
    }

    public String insertRevision(String kbId, IndexVersion version, IndexRevision.IndexStatus status,
                                 String modelCatalogId, String modelName, int dimensions) {
        return jdbc.queryForObject("INSERT INTO exobrain_index_revision (kb_id, index_version, status, "
                        + "model_catalog_id, model_name, dimensions) VALUES (CAST(? AS uuid), ?, ?, ?, ?, ?) "
                        + "RETURNING id",
                String.class, kbId, version.wire(), status.name(), modelCatalogId, modelName, dimensions);
    }

    public void publishRevision(String revisionId, int documentCount, int chunkCount, String sourceSetHash) {
        jdbc.update("UPDATE exobrain_index_revision SET status = ?, published_at = NOW(), superseded_at = NULL, "
                        + "document_count = ?, chunk_count = ?, source_set_hash = ? WHERE id = CAST(? AS uuid)",
                IndexRevision.IndexStatus.PUBLISHED.name(), documentCount, chunkCount, sourceSetHash, revisionId);
    }

    public void supersedeRevision(String revisionId) {
        jdbc.update("UPDATE exobrain_index_revision SET status = ?, superseded_at = NOW() "
                        + "WHERE id = CAST(? AS uuid) AND status = ?",
                IndexRevision.IndexStatus.SUPERSEDED.name(), revisionId,
                IndexRevision.IndexStatus.PUBLISHED.name());
    }

    public void upsertState(String kbId, String revisionId, IndexVersion version) {
        jdbc.update("INSERT INTO exobrain_kb_index_state (kb_id, active_revision_id, active_index_version, updated_at) "
                        + "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, NOW()) "
                        + "ON CONFLICT (kb_id) DO UPDATE SET active_revision_id = EXCLUDED.active_revision_id, "
                        + "active_index_version = EXCLUDED.active_index_version, updated_at = NOW()",
                kbId, revisionId, version.wire());
    }

    /** 复制基础版本里未变化文档的 chunk（向量在库内复制，不调用外部服务）。 */
    public int copyChunks(String baseRevisionId, String newRevisionId, IndexVersion version,
                          Collection<String> freshDocumentIds) {
        StringBuilder sql = new StringBuilder("INSERT INTO chunk_bge_m3 (kb_id, doc_id, content, metadata, embedding, "
                + "revision_id, index_version, chunk_ordinal, source_hash, start_line, end_line, created_at, updated_at) "
                + "SELECT kb_id, doc_id, content, metadata, embedding, CAST(? AS uuid), ?, chunk_ordinal, source_hash, "
                + "start_line, end_line, NOW(), NOW() FROM chunk_bge_m3 WHERE revision_id = CAST(? AS uuid)");
        List<Object> args = new ArrayList<>();
        args.add(newRevisionId);
        args.add(version.wire());
        args.add(baseRevisionId);
        if (freshDocumentIds != null && !freshDocumentIds.isEmpty()) {
            sql.append(" AND doc_id NOT IN (");
            for (int i = 0; i < freshDocumentIds.size(); i++) {
                sql.append(i == 0 ? "CAST(? AS uuid)" : ", CAST(? AS uuid)");
            }
            sql.append(")");
            args.addAll(freshDocumentIds);
        }
        return jdbc.update(sql.toString(), args.toArray());
    }

    public int countChunksForRevision(String revisionId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM chunk_bge_m3 WHERE revision_id = CAST(? AS uuid)",
                Integer.class, revisionId);
        return count == null ? 0 : count;
    }

    /** 一个已发布版本覆盖的（文档, 源 hash）集合；hash 取该文档各 chunk 的同一个值。 */
    public List<SourceRef> loadRevisionSourceSet(String revisionId) {
        return jdbc.query("SELECT doc_id, min(source_hash) AS source_hash FROM chunk_bge_m3 "
                        + "WHERE revision_id = CAST(? AS uuid) GROUP BY doc_id ORDER BY doc_id",
                (rs, row) -> new SourceRef(rs.getString("doc_id"), rs.getString("source_hash")), revisionId);
    }

    /** KB 当前的文档清单（用于发布前核对候选是否与线上一致）。 */
    public List<String> listDocumentIds(String kbId) {
        return jdbc.query("SELECT id FROM document WHERE kb_id = CAST(? AS uuid) ORDER BY id",
                (rs, row) -> rs.getString("id"), kbId);
    }

    /** 文档显示名（检索时按命中片段一次性查询，避免每个 chunk 一次往返）。 */
    public Map<String, String> documentNames(Collection<String> documentIds) {
        Map<String, String> names = new HashMap<>();
        if (documentIds == null || documentIds.isEmpty()) {
            return names;
        }
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id, filename FROM document WHERE id IN (");
        int index = 0;
        for (String documentId : documentIds) {
            sql.append(index++ == 0 ? "CAST(? AS uuid)" : ", CAST(? AS uuid)");
            args.add(documentId);
        }
        sql.append(")");
        for (Map.Entry<String, String> row : jdbc.query(sql.toString(), (rs, rowNumber) -> Map.entry(
                rs.getString("id").toLowerCase(Locale.ROOT),
                rs.getString("filename") == null ? "" : rs.getString("filename")), args.toArray())) {
            names.put(row.getKey(), row.getValue());
        }
        return names;
    }

    /**
     * 文档删除后刷新活动版本的计数：计数是事实，删除会让它变旧，必须重算；
     * {@code source_set_hash} 置空，避免用一个已经不再代表当前文档集合的 hash 声称候选一致。
     */
    public int refreshActiveRevision(String kbId) {
        return jdbc.update("UPDATE exobrain_index_revision SET "
                + "document_count = (SELECT count(*) FROM document d WHERE d.kb_id = exobrain_index_revision.kb_id), "
                + "chunk_count = (SELECT count(*) FROM chunk_bge_m3 c WHERE c.revision_id = exobrain_index_revision.id), "
                + "source_set_hash = NULL "
                + "WHERE id = (SELECT active_revision_id FROM exobrain_kb_index_state WHERE kb_id = CAST(? AS uuid)) "
                + "AND status = ?", kbId, IndexRevision.IndexStatus.PUBLISHED.name());
    }

    /** 清理被替换的版本（chunk 通过外键级联删除）；活动版本与未发布版本不会被删。 */
    public int deleteSupersededRevisions(String kbId, int keepNewest) {
        List<String> candidates = jdbc.query("SELECT id FROM exobrain_index_revision WHERE kb_id = CAST(? AS uuid) "
                        + "AND status = ? AND id <> COALESCE((SELECT active_revision_id FROM exobrain_kb_index_state "
                        + "WHERE kb_id = CAST(? AS uuid)), '00000000-0000-0000-0000-000000000000'::uuid) "
                        + "ORDER BY superseded_at DESC NULLS LAST, created_at DESC OFFSET ?",
                (rs, row) -> rs.getString("id"), kbId, IndexRevision.IndexStatus.SUPERSEDED.name(), kbId,
                Math.max(0, keepNewest));
        int deleted = 0;
        for (String revisionId : candidates) {
            deleted += jdbc.update("DELETE FROM exobrain_index_revision WHERE id = CAST(? AS uuid) AND status = ?",
                    revisionId, IndexRevision.IndexStatus.SUPERSEDED.name());
        }
        return deleted;
    }

    /**
     * 非活动版本的 chunk 删除（保留版本行本身），用于按保留策略回收向量数据。
     * 用 CTE 先选出可删的行，避免在 DELETE 的子查询里引用目标表。
     */
    public int deleteRevisionChunks(String revisionId) {
        return jdbc.update("WITH deletable AS (SELECT c.id FROM chunk_bge_m3 c "
                + "LEFT JOIN exobrain_kb_index_state s ON s.kb_id = c.kb_id "
                + "WHERE c.revision_id = CAST(? AS uuid) "
                + "AND (s.active_revision_id IS NULL OR s.active_revision_id <> CAST(? AS uuid))) "
                + "DELETE FROM chunk_bge_m3 WHERE id IN (SELECT id FROM deletable)", revisionId, revisionId);
    }
    /** 重新激活一个曾经发布过的版本（回滚）：保留它第一次发布的时间，不假装是新的发布。 */
    public void activateRevision(String revisionId) {
        jdbc.update("UPDATE exobrain_index_revision SET status = ?, superseded_at = NULL, "
                        + "published_at = COALESCE(published_at, NOW()) WHERE id = CAST(? AS uuid) AND status = ?",
                IndexRevision.IndexStatus.PUBLISHED.name(), revisionId,
                IndexRevision.IndexStatus.SUPERSEDED.name());
    }}
