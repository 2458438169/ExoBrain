package com.kama.jchatmind.auth;

/** Request identity is never inherited by executor threads; queued work carries its own identity. */
public final class AuthContext {
    private static final ThreadLocal<AuthRepository.LoginSession> CURRENT = new ThreadLocal<>();
    private AuthContext() { }
    static void set(AuthRepository.LoginSession session) { CURRENT.set(session); }
    static void clear() { CURRENT.remove(); }
    public static AuthUser requireUser() { return requireSession().user(); }
    public static AuthUser requireAdmin() {
        AuthUser user = requireUser();
        if (!user.isAdmin()) throw AuthException.forbidden();
        return user;
    }
    public static AuthRepository.LoginSession requireSession() {
        var session = CURRENT.get();
        if (session == null || !session.user().enabled()) throw AuthException.unauthorized();
        return session;
    }
}
