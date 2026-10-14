package com.kama.jchatmind.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {
    public record LoginResult(AuthUser user, String token, String csrfToken, Instant expiresAt) { }
    public record CreateUser(String username, String displayName, String password, String role, Boolean enabled) { }
    public record UpdateUser(String displayName, String role, Boolean enabled, String password) { }
    private final AuthRepository repository;
    private final PasswordHasher passwords;
    private final AuthProperties properties;
    private final LoginRateLimiter limiter;
    private final String dummyHash;
    public AuthService(AuthRepository repository, PasswordHasher passwords, AuthProperties properties, LoginRateLimiter limiter) {
        this.repository = repository; this.passwords = passwords; this.properties = properties; this.limiter = limiter;
        dummyHash = passwords.hash(AuthTokens.random());
    }
    @Transactional
    public LoginResult login(String suppliedUsername, String password, String peer) {
        String username = suppliedUsername == null ? "" : suppliedUsername.strip().toLowerCase(Locale.ROOT);
        if (username.length() > 64 || password == null || password.length() > 128) throw invalidLogin();
        limiter.attempt(username, peer == null ? "unknown" : peer);
        AuthRepository.StoredUser stored = repository.findByUsernameForUpdate(username);
        boolean matches = passwords.verify(password, stored == null ? dummyHash : stored.passwordHash());
        if (stored == null || !matches || !stored.user().enabled()) throw invalidLogin();
        String token = AuthTokens.random();
        String csrfToken = AuthTokens.random();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(Math.clamp(properties.getSessionHours(), 1, 168)));
        repository.insertSession(stored.user().id(), AuthTokens.hash(token), csrfToken, expiresAt, stored.authVersion());
        return new LoginResult(stored.user(), token, csrfToken, expiresAt);
    }
    public AuthRepository.LoginSession authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        return repository.findSession(AuthTokens.hash(token));
    }
    public void logout() { repository.revokeSession(AuthContext.requireSession().tokenHash()); }
    public List<AuthUser> users() { AuthContext.requireAdmin(); return repository.users(); }
    @Transactional
    public AuthUser create(CreateUser request) {
        AuthContext.requireAdmin();
        repository.lockAdministration();
        return insert(request);
    }
    @Transactional
    public boolean bootstrap(String username, String password) {
        repository.lockAdministration();
        if (repository.countUsers() > 0) return false;
        insert(new CreateUser(username, "管理员", password, "ADMIN", true));
        return true;
    }
    private AuthUser insert(CreateUser request) {
        String username = username(request.username());
        validatePassword(request.password());
        AuthUser user = new AuthUser(UUID.randomUUID().toString(), username, displayName(request.displayName(), username),
                role(request.role() == null ? "USER" : request.role()), request.enabled() == null || request.enabled(), Instant.now());
        try { repository.insertUser(user, passwords.hash(request.password())); }
        catch (DuplicateKeyException duplicate) { throw new AuthException(409, "账号已存在"); }
        return user;
    }
    @Transactional
    public AuthUser update(String id, UpdateUser request) {
        AuthContext.requireAdmin();
        ResourceAccess.validId(id);
        repository.lockAdministration();
        AuthRepository.StoredUser stored = repository.findByIdForUpdate(id);
        if (stored == null) throw AuthException.missing();
        AuthUser before = stored.user();
        String changedRole = request.role() == null ? before.role() : role(request.role());
        boolean enabled = request.enabled() == null ? before.enabled() : request.enabled();
        if (before.isAdmin() && before.enabled() && (!"ADMIN".equals(changedRole) || !enabled)
                && repository.countEnabledAdmins() <= 1) throw new AuthException(409, "必须保留至少一个启用的管理员");
        String passwordHash = stored.passwordHash();
        if (request.password() != null) { validatePassword(request.password()); passwordHash = passwords.hash(request.password()); }
        AuthUser after = new AuthUser(before.id(), before.username(), request.displayName() == null ? before.displayName()
                : displayName(request.displayName(), before.username()), changedRole, enabled, before.createdAt());
        repository.updateUser(after, passwordHash);
        return after;
    }
    @Transactional
    public void changePassword(String currentPassword, String newPassword) {
        AuthUser actor = AuthContext.requireUser();
        AuthRepository.StoredUser stored = repository.findByIdForUpdate(actor.id());
        if (stored == null || !stored.user().enabled() || !passwords.verify(currentPassword, stored.passwordHash()))
            throw new AuthException(400, "当前密码不正确");
        validatePassword(newPassword);
        repository.updateUser(stored.user(), passwords.hash(newPassword));
    }
    public AuthUser currentActor(String userId, int version, String tokenHash) {
        if (userId == null || tokenHash == null || !tokenHash.matches("[a-f0-9]{64}")) throw AuthException.unauthorized();
        ResourceAccess.validId(userId);
        var session = repository.findSession(tokenHash);
        if (session == null || !session.user().enabled() || session.authVersion() != version
                || !userId.equals(session.user().id()) || !tokenHash.equals(session.tokenHash())
                || !session.expiresAt().isAfter(Instant.now())) throw AuthException.unauthorized();
        return session.user();
    }
    static String username(String value) {
        if (value == null || !value.strip().matches("[A-Za-z0-9][A-Za-z0-9_.-]{1,31}"))
            throw new AuthException(400, "账号需为 2–32 位字母、数字、点、下划线或短横线");
        return value.strip().toLowerCase(Locale.ROOT);
    }
    static String displayName(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        if (value.strip().length() > 64) throw new AuthException(400, "显示名称不能超过 64 字符");
        return value.strip();
    }
    static void validatePassword(String value) {
        if (value == null || value.length() < 6 || value.length() > 128 || value.isBlank())
            throw new AuthException(400, "密码长度需为 6–128 字符");
    }
    private static String role(String value) {
        if (!"ADMIN".equals(value) && !"USER".equals(value)) throw new AuthException(400, "角色无效");
        return value;
    }
    private static AuthException invalidLogin() { return new AuthException(401, "账号或密码不正确"); }
}
