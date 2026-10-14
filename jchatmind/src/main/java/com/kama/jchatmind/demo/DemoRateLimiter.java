package com.kama.jchatmind.demo;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

@Component
public class DemoRateLimiter {
    private static final long HOUR = 3_600_000;
    private final Clock clock;
    private final Map<String, ArrayDeque<Long>> requests = new HashMap<>();

    public DemoRateLimiter() { this(Clock.systemUTC()); }
    DemoRateLimiter(Clock clock) { this.clock = clock; }

    public synchronized void acquire(String owner) {
        long now = clock.millis();
        requests.entrySet().removeIf(entry -> entry.getValue().isEmpty()
                || entry.getValue().peekLast() <= now - HOUR);
        ArrayDeque<Long> history = requests.computeIfAbsent(owner, ignored -> new ArrayDeque<>());
        while (!history.isEmpty() && history.peekFirst() <= now - HOUR) history.removeFirst();
        if ((!history.isEmpty() && now - history.peekLast() < 3000) || history.size() >= 60) {
            throw new DemoException(429, "体验请求过于频繁，请稍后重试（每 3 秒一次，每小时最多 60 次）");
        }
        history.addLast(now);
    }
}
