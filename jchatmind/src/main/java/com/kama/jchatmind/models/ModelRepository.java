package com.kama.jchatmind.models;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ModelRepository {
    private static final String COLUMNS = "id, name, kind, protocol, base_url, provider_model, "
            + "encrypted_api_key, enabled, default_model, dimensions, created_at, updated_at";
    private static final RowMapper<ModelDefinition> ROW = (rs, row) -> new ModelDefinition(
            rs.getString("id"), rs.getString("name"), ModelKind.valueOf(rs.getString("kind")),
            rs.getString("protocol"), rs.getString("base_url"), rs.getString("provider_model"),
            rs.getString("encrypted_api_key"), rs.getBoolean("enabled"), rs.getBoolean("default_model"),
            rs.getObject("dimensions", Integer.class), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());
    private final JdbcTemplate jdbc;

    public ModelRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Serialize administrative changes and new KB bindings for this transaction. */
    public boolean lockCatalog() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT bootstrapped FROM exobrain_model_state WHERE id = 1 FOR UPDATE", Boolean.class));
    }

    public void markBootstrapped() {
        jdbc.update("UPDATE exobrain_model_state SET bootstrapped = TRUE WHERE id = 1");
    }

    public List<ModelDefinition> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM exobrain_model ORDER BY kind, created_at, id", ROW);
    }

    public Optional<ModelDefinition> find(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exobrain_model WHERE id = ?", ROW, id).stream().findFirst();
    }

    public Optional<ModelDefinition> findDefaultEmbedding() {
        return jdbc.query("SELECT " + COLUMNS + " FROM exobrain_model "
                + "WHERE kind = 'EMBEDDING' AND default_model AND enabled", ROW).stream().findFirst();
    }

    public void insert(ModelDefinition model) {
        jdbc.update("INSERT INTO exobrain_model (id, name, kind, protocol, base_url, provider_model, "
                        + "encrypted_api_key, enabled, default_model, dimensions) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                model.id(), model.name(), model.kind().name(), model.protocol(), model.baseUrl(), model.model(),
                model.encryptedApiKey(), model.enabled(), model.defaultModel(), model.dimensions());
    }

    public void update(ModelDefinition model) {
        jdbc.update("UPDATE exobrain_model SET name = ?, protocol = ?, base_url = ?, provider_model = ?, "
                        + "encrypted_api_key = ?, enabled = ?, default_model = ?, dimensions = ?, updated_at = NOW() WHERE id = ?",
                model.name(), model.protocol(), model.baseUrl(), model.model(), model.encryptedApiKey(),
                model.enabled(), model.defaultModel(), model.dimensions(), model.id());
    }

    public void clearDefault(ModelKind kind) {
        jdbc.update("UPDATE exobrain_model SET default_model = FALSE, updated_at = NOW() "
                + "WHERE kind = ? AND default_model", kind.name());
    }

    public boolean isReferenced(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM agent WHERE model = ?) "
                + "OR EXISTS (SELECT 1 FROM exobrain_kb_embedding WHERE model_id = ?)", Boolean.class, id, id));
    }

    public boolean hasKnowledgeBaseBinding(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM exobrain_kb_embedding WHERE model_id = ?)", Boolean.class, id));
    }

    public void delete(String id) { jdbc.update("DELETE FROM exobrain_model WHERE id = ?", id); }

    public void bindExistingKnowledgeBases(String modelId) {
        jdbc.update("INSERT INTO exobrain_kb_embedding (kb_id, model_id) "
                + "SELECT id, ? FROM knowledge_base ON CONFLICT (kb_id) DO NOTHING", modelId);
    }

    public void bindKnowledgeBase(String kbId, String modelId) {
        jdbc.update("INSERT INTO exobrain_kb_embedding (kb_id, model_id) VALUES (CAST(? AS uuid), ?) "
                + "ON CONFLICT (kb_id) DO NOTHING", kbId, modelId);
    }

    public Optional<ModelDefinition> findKnowledgeBaseModel(String kbId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exobrain_model WHERE id = "
                + "(SELECT model_id FROM exobrain_kb_embedding WHERE kb_id = CAST(? AS uuid))", ROW, kbId)
                .stream().findFirst();
    }
}
