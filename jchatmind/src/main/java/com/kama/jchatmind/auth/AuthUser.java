package com.kama.jchatmind.auth;

import java.time.Instant;

public record AuthUser(String id, String username, String displayName, String role,
                       boolean enabled, Instant createdAt) {
    public boolean isAdmin() { return "ADMIN".equals(role); }
}
