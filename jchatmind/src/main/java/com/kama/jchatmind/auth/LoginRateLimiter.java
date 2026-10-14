package com.kama.jchatmind.auth;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Bound both the username and peer, including attempts against nonexistent accounts. */
@Component
public class LoginRateLimiter {
    private record Bucket(long expires, int count) { }
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final Clock clock;
    public LoginRateLimiter() { this(Clock.systemUTC()); }
    LoginRateLimiter(Clock clock) { this.clock = clock; }
    public synchronized void attempt(String username, String peer) {
        long now = clock.millis();
        buckets.entrySet().removeIf(entry -> entry.getValue().expires <= now);
        if (buckets.size() >= 10_000) throw limited();
        consume("user:" + AuthTokens.hash(username), 10, now);
        consume("ip:" + AuthTokens.hash(peer), 50, now);
    }
    private void consume(String key, int maximum, long now) {
        Bucket old = buckets.getOrDefault(key, new Bucket(now + Duration.ofMinutes(5).toMillis(), 0));
        if (old.count >= maximum) throw limited();
        buckets.put(key, new Bucket(old.expires, old.count + 1));
    }
    private static AuthException limited() { return new AuthException(429, "登录尝试过于频繁，请稍后重试"); }
}
