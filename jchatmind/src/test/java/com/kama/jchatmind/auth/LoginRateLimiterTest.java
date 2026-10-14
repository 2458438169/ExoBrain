package com.kama.jchatmind.auth;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;

class LoginRateLimiterTest {
    @Test void limitsRepeatedUsernamesEvenWhenPeerChanges() {
        var limiter = new LoginRateLimiter(Clock.fixed(Instant.now(), ZoneOffset.UTC));
        for (int i = 0; i < 10; i++) limiter.attempt("demo", "peer-" + i);
        assertEquals(429, assertThrows(AuthException.class, () -> limiter.attempt("demo", "another-peer")).getStatus());
    }
    @Test void limitsPasswordSprayingAcrossManyUsernames() {
        var limiter = new LoginRateLimiter();
        for (int i = 0; i < 50; i++) limiter.attempt("demo-" + i, "same-peer");
        assertEquals(429, assertThrows(AuthException.class, () -> limiter.attempt("another-user", "same-peer")).getStatus());
    }
}
