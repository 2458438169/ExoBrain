package com.kama.jchatmind.demo;

import com.kama.jchatmind.model.vo.ChatSessionVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class DemoSessionRepository {
    private static final String OWNER = "agent_id = CAST(? AS uuid) AND metadata ->> 'demoOwner' = ?";
    private static final RowMapper<ChatSessionVO> ROW = (rs, row) -> ChatSessionVO.builder()
            .id(rs.getString("id")).agentId(rs.getString("agent_id")).title(rs.getString("title")).build();
    private final JdbcTemplate jdbc;

    public DemoSessionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lockOwner(String owner) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", owner);
    }

    public List<ChatSessionVO> list(String agentId, String owner) {
        return jdbc.query("SELECT id, agent_id, title FROM chat_session WHERE " + OWNER
                + " ORDER BY updated_at DESC, id DESC", ROW, agentId, owner);
    }

    public ChatSessionVO find(String id, String agentId, String owner) {
        List<ChatSessionVO> found = jdbc.query("SELECT id, agent_id, title FROM chat_session WHERE id = CAST(? AS uuid) AND "
                + OWNER, ROW, id, agentId, owner);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Only sessions created through the visitor flow can be published, never administrator history. */
    public ChatSessionVO findShowcase(String id, String agentId) {
        List<ChatSessionVO> found = jdbc.query("SELECT id, agent_id, title FROM chat_session "
                + "WHERE id = CAST(? AS uuid) AND agent_id = CAST(? AS uuid) "
                + "AND metadata ->> 'demoOwner' ~ '^[a-f0-9]{64}$'", ROW, id, agentId);
        return found.isEmpty() ? null : found.get(0);
    }

    public void insert(String id, String agentId, String title, String owner) {
        jdbc.update("INSERT INTO chat_session (id, agent_id, title, metadata, created_at, updated_at) "
                        + "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, jsonb_build_object('demoOwner', CAST(? AS text)), NOW(), NOW())",
                id, agentId, title, owner);
    }

    public boolean rename(String id, String agentId, String owner, String title) {
        return jdbc.update("UPDATE chat_session SET title = ?, updated_at = NOW() WHERE id = CAST(? AS uuid) AND "
                + OWNER, title, id, agentId, owner) == 1;
    }

    public boolean delete(String id, String agentId, String owner) {
        return jdbc.update("DELETE FROM chat_session WHERE id = CAST(? AS uuid) AND " + OWNER,
                id, agentId, owner) == 1;
    }
}
