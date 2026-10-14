package com.kama.jchatmind.demo;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DemoRateLimiterTest {
    @Test
    void enforcesSpacingAndHourlyQuotaSeparatelyForEachVisitor() {
        MutableClock clock = new MutableClock();
        DemoRateLimiter limiter = new DemoRateLimiter(clock);
        limiter.acquire("first");
        assertEquals(429, assertThrows(DemoException.class, () -> limiter.acquire("first")).getStatus());
        assertDoesNotThrow(() -> limiter.acquire("second"));
        for (int i = 1; i < 60; i++) {
            clock.millis += 3000;
            limiter.acquire("first");
        }
        clock.millis += 3000;
        assertEquals(429, assertThrows(DemoException.class, () -> limiter.acquire("first")).getStatus());
        clock.millis += 3_600_000;
        assertDoesNotThrow(() -> limiter.acquire("first"));
    }

    private static class MutableClock extends Clock {
        long millis = 1_000_000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
