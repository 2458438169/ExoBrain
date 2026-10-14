package com.kama.jchatmind.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class AuthRepository {
    public record StoredUser(AuthUser user, String passwordHash, int authVersion) { }
    public record LoginSession(AuthUser user, String tokenHash, String csrfToken, Instant expiresAt, int authVersion) { }
    private static final String USER_COLUMNS = "id, username, display_name, role, enabled, created_at, password_hash, auth_version";
    private static final RowMapper<StoredUser> USER = (rs, row) -> new StoredUser(
            new AuthUser(rs.getString("id"), rs.getString("username"), rs.getString("display_name"),
                    rs.getString("role"), rs.getBoolean("enabled"), rs.getTimestamp("created_at").toInstant()),
            rs.getString("password_hash"), rs.getInt("auth_version"));
    private final JdbcTemplate jdbc;
    public AuthRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void lockAdministration() {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext('exobrain-account-administration'))");
    }
    public long countUsers() { return jdbc.queryForObject("SELECT count(*) FROM exobrain_user", Long.class); }
    public long countEnabledAdmins() {
        return jdbc.queryForObject("SELECT count(*) FROM exobrain_user WHERE enabled AND role = 'ADMIN'", Long.class);
    }
    public List<AuthUser> users() {
        return jdbc.query("SELECT " + USER_COLUMNS + " FROM exobrain_user ORDER BY created_at, id", USER)
                .stream().map(StoredUser::user).toList();
    }
    public StoredUser findByUsernameForUpdate(String username) {
        return first(jdbc.query("SELECT " + USER_COLUMNS + " FROM exobrain_user WHERE username = ? FOR UPDATE", USER, username));
    }
    public StoredUser findById(String id) {
        return first(jdbc.query("SELECT " + USER_COLUMNS + " FROM exobrain_user WHERE id = CAST(? AS uuid)", USER, id));
    }
    public StoredUser findByIdForUpdate(String id) {
        return first(jdbc.query("SELECT " + USER_COLUMNS + " FROM exobrain_user WHERE id = CAST(? AS uuid) FOR UPDATE", USER, id));
    }
    public void insertUser(AuthUser user, String passwordHash) {
        jdbc.update("INSERT INTO exobrain_user (id, username, display_name, role, enabled, password_hash) "
                        + "VALUES (CAST(? AS uuid), ?, ?, ?, ?, ?)", user.id(), user.username(), user.displayName(),
                user.role(), user.enabled(), passwordHash);
    }
    public void updateUser(AuthUser user, String passwordHash) {
        jdbc.update("UPDATE exobrain_user SET display_name = ?, role = ?, enabled = ?, password_hash = ?, "
                        + "auth_version = auth_version + 1, updated_at = NOW() WHERE id = CAST(? AS uuid)",
                user.displayName(), user.role(), user.enabled(), passwordHash, user.id());
        revokeUserSessions(user.id());
    }
    public void insertSession(String userId, String tokenHash, String csrfToken, Instant expiresAt, int authVersion) {
        jdbc.update("DELETE FROM exobrain_login_session WHERE expires_at <= NOW()");
        jdbc.update("INSERT INTO exobrain_login_session (token_hash, user_id, csrf_token, expires_at, auth_version) "
                + "VALUES (?, CAST(? AS uuid), ?, ?, ?)", tokenHash, userId, csrfToken, Timestamp.from(expiresAt), authVersion);
    }
    public LoginSession findSession(String tokenHash) {
        List<LoginSession> found = jdbc.query("SELECT u.*, s.token_hash, s.csrf_token, s.expires_at FROM exobrain_login_session s "
                        + "JOIN exobrain_user u ON u.id = s.user_id WHERE s.token_hash = ? AND s.expires_at > NOW() "
                        + "AND u.enabled AND s.auth_version = u.auth_version", (rs, row) -> new LoginSession(
                        USER.mapRow(rs, row).user(), rs.getString("token_hash"), rs.getString("csrf_token"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getInt("auth_version")), tokenHash);
        return found.isEmpty() ? null : found.get(0);
    }
    public void revokeSession(String tokenHash) { jdbc.update("DELETE FROM exobrain_login_session WHERE token_hash = ?", tokenHash); }
    public void revokeUserSessions(String id) {
        jdbc.update("DELETE FROM exobrain_login_session WHERE user_id = CAST(? AS uuid)", id);
    }
    private static StoredUser first(List<StoredUser> values) { return values.isEmpty() ? null : values.get(0); }
}
