package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.SseService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class SseServiceImpl implements SseService {

    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService heartbeatScheduler;

    @Autowired
    public SseServiceImpl(ObjectMapper objectMapper) {
        this(objectMapper, Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        }));
    }

    SseServiceImpl(ObjectMapper objectMapper, ScheduledExecutorService heartbeatScheduler) {
        this.objectMapper = objectMapper;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @PostConstruct
    void startHeartbeats() {
        heartbeatScheduler.scheduleWithFixedDelay(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopHeartbeats() {
        heartbeatScheduler.shutdownNow();
    }

    private void sendHeartbeats() {
        clients.forEach((sessionId, emitter) -> {
            try {
                // SSE 注释维持代理连接，不会触发前端的 message 事件。
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | RuntimeException e) {
                removeFailedEmitter(sessionId, emitter, e);
            }
        });
    }

    private void removeFailedEmitter(String sessionId, SseEmitter emitter, Exception failure) {
        clients.remove(sessionId, emitter);
        try {
            emitter.completeWithError(failure);
        } catch (RuntimeException ignored) {
            // 已关闭的连接不能中断其余客户端的心跳。
        }
    }

    @Override
    public SseEmitter connect(String chatSessionId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitter.onCompletion(() -> clients.remove(chatSessionId, emitter));
        emitter.onTimeout(() -> clients.remove(chatSessionId, emitter));
        emitter.onError((error) -> clients.remove(chatSessionId, emitter));

        SseEmitter previous = clients.put(chatSessionId, emitter);
        if (previous != null) {
            previous.complete();
        }

        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data("connected")
            );
        } catch (IOException | RuntimeException e) {
            removeFailedEmitter(chatSessionId, emitter, e);
            throw new RuntimeException(e);
        }

        return emitter;
    }

    @Override
    public void send(String chatSessionId, SseMessage message) {
        SseEmitter emitter = clients.get(chatSessionId);

        if (emitter != null) {
            try {
                // 将消息转换为字符串
                String sseMessageStr = objectMapper.writeValueAsString(message);
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(sseMessageStr)
                );
            } catch (IOException | RuntimeException e) {
                removeFailedEmitter(chatSessionId, emitter, e);
                throw new RuntimeException(e);
            }
        } else {
            throw new RuntimeException("No client found for chatSessionId: " + chatSessionId);
        }
    }
}
