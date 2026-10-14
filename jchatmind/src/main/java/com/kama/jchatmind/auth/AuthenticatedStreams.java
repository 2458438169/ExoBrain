package com.kama.jchatmind.auth;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Long-lived SSE connections are closed when their login session is revoked or expires. */
@Component
public class AuthenticatedStreams {
    private final AuthRepository repository;
    private final ConcurrentHashMap<SseEmitter, String> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "account-stream-expiry"); thread.setDaemon(true); return thread;
    });
    public AuthenticatedStreams(AuthRepository repository) { this.repository = repository; }
    @PostConstruct void start() { scheduler.scheduleWithFixedDelay(this::expire, 15, 15, TimeUnit.SECONDS); }
    @PreDestroy void stop() { scheduler.shutdownNow(); connections.keySet().forEach(SseEmitter::complete); connections.clear(); }
    public SseEmitter track(SseEmitter emitter) {
        connections.put(emitter, AuthContext.requireSession().tokenHash());
        emitter.onCompletion(() -> connections.remove(emitter));
        emitter.onError(error -> connections.remove(emitter));
        emitter.onTimeout(() -> connections.remove(emitter));
        return emitter;
    }
    void expire() {
        connections.forEach((emitter, hash) -> {
            try { if (repository.findSession(hash) != null) return; }
            catch (RuntimeException unavailable) { /* fail closed if authorization cannot be verified */ }
            connections.remove(emitter);
            try { emitter.complete(); } catch (RuntimeException ignored) { }
        });
    }
}
