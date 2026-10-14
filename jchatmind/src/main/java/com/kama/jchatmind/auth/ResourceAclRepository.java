package com.kama.jchatmind.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class ResourceAclRepository {
    private final JdbcTemplate jdbc;
    public ResourceAclRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean owns(String userId, String type, String id) {
        return userId.equals(owner(type, id));
    }
    public String owner(String type, String id) {
        List<String> owners = jdbc.queryForList("SELECT owner_id::text FROM exobrain_resource_acl "
                + "WHERE resource_type = ? AND resource_id = CAST(? AS uuid)", String.class, type, id);
        return owners.size() == 1 ? owners.get(0) : null;
    }
    public void claim(String userId, String type, String id) {
        jdbc.update("INSERT INTO exobrain_resource_acl (resource_type, resource_id, owner_id) "
                + "VALUES (?, CAST(? AS uuid), CAST(? AS uuid))", type, id, userId);
    }
    public void remove(String type, String id) {
        jdbc.update("DELETE FROM exobrain_resource_acl WHERE resource_type = ? AND resource_id = CAST(? AS uuid)", type, id);
    }
}
