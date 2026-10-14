package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.message.SseMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SseServiceImplTest {

    @Test
    void scheduledHeartbeatSendsCommentFramesAndStopsWithService() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        try (var construction = mockConstruction(SseEmitter.class)) {
            SseServiceImpl service = new SseServiceImpl(new ObjectMapper(), scheduler);
            Runnable heartbeat = startAndCaptureHeartbeat(service, scheduler);
            SseEmitter emitter = service.connect("session");

            heartbeat.run();
            heartbeat.run();

            ArgumentCaptor<SseEmitter.SseEventBuilder> events = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
            verify(emitter, times(3)).send(events.capture());
            for (SseEmitter.SseEventBuilder event : events.getAllValues().subList(1, 3)) {
                assertEquals(":keepalive\n\n", event.build().stream()
                        .map(data -> data.getData().toString()).collect(Collectors.joining()));
            }
            service.stopHeartbeats();
            verify(scheduler).shutdownNow();
        }
    }

    @Test
    void failedHeartbeatRemovesDeadConnectionAndOtherClientsKeepReceivingHeartbeats() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        try (var construction = mockConstruction(SseEmitter.class)) {
            SseServiceImpl service = new SseServiceImpl(new ObjectMapper(), scheduler);
            Runnable heartbeat = startAndCaptureHeartbeat(service, scheduler);
            SseEmitter failed = service.connect("failed");
            SseEmitter healthy = service.connect("healthy");
            IOException failure = new IOException("Broken pipe");
            doThrow(failure).when(failed).send(any(SseEmitter.SseEventBuilder.class));

            heartbeat.run();
            heartbeat.run();

            verify(failed, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            verify(failed).completeWithError(failure);
            verify(healthy, times(3)).send(any(SseEmitter.SseEventBuilder.class));
            assertThrows(RuntimeException.class, () -> service.send("failed", doneMessage()));
        }
    }

    @Test
    void heartbeatFailureFromReplacedConnectionPreservesNewConnection() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        try (var construction = mockConstruction(SseEmitter.class)) {
            SseServiceImpl service = new SseServiceImpl(new ObjectMapper(), scheduler);
            Runnable heartbeat = startAndCaptureHeartbeat(service, scheduler);
            SseEmitter first = service.connect("session");
            AtomicReference<SseEmitter> replacement = new AtomicReference<>();
            doAnswer(invocation -> {
                replacement.set(service.connect("session"));
                throw new IOException("old connection closed during heartbeat");
            }).when(first).send(any(SseEmitter.SseEventBuilder.class));

            heartbeat.run();
            service.send("session", doneMessage());

            verify(replacement.get(), times(2)).send(any(SseEmitter.SseEventBuilder.class));
        }
    }

    @Test
    void normalMessageSendFailureAlsoRemovesDeadConnection() throws Exception {
        try (var construction = mockConstruction(SseEmitter.class)) {
            SseServiceImpl service = new SseServiceImpl(new ObjectMapper(), mock(ScheduledExecutorService.class));
            SseEmitter emitter = service.connect("session");
            IOException failure = new IOException("Broken pipe");
            doThrow(failure).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

            assertThrows(RuntimeException.class, () -> service.send("session", doneMessage()));
            assertThrows(RuntimeException.class, () -> service.send("session", doneMessage()));

            verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter).completeWithError(failure);
        }
    }

    @Test
    void staleConnectionCallbacksCannotRemoveItsReplacement() throws Exception {
        List<Callbacks> callbacks = new ArrayList<>();
        try (var construction = mockConstruction(SseEmitter.class, (emitter, context) -> {
            Callbacks lifecycle = new Callbacks();
            callbacks.add(lifecycle);
            doAnswer(invocation -> { lifecycle.completion = invocation.getArgument(0); return null; })
                    .when(emitter).onCompletion(any());
            doAnswer(invocation -> { lifecycle.timeout = invocation.getArgument(0); return null; })
                    .when(emitter).onTimeout(any());
            doAnswer(invocation -> { lifecycle.error = invocation.getArgument(0); return null; })
                    .when(emitter).onError(any());
        })) {
            SseServiceImpl service = new SseServiceImpl(new ObjectMapper());
            SseEmitter first = service.connect("session");
            SseEmitter replacement = service.connect("session");
            verify(first).complete();

            callbacks.get(0).completion.run();
            callbacks.get(0).timeout.run();
            callbacks.get(0).error.accept(new IllegalStateException("old connection closed"));
            service.send("session", SseMessage.builder().type(SseMessage.Type.AI_DONE).build());
            verify(replacement, times(2)).send(any(SseEmitter.SseEventBuilder.class));

            callbacks.get(1).completion.run();
            assertThrows(RuntimeException.class, () -> service.send("session",
                    SseMessage.builder().type(SseMessage.Type.AI_DONE).build()));
        }
    }

    private static class Callbacks {
        Runnable completion;
        Runnable timeout;
        Consumer<Throwable> error;
    }

    private static Runnable startAndCaptureHeartbeat(SseServiceImpl service, ScheduledExecutorService scheduler) {
        service.startHeartbeats();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(task.capture(), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
        return task.getValue();
    }

    private static SseMessage doneMessage() {
        return SseMessage.builder().type(SseMessage.Type.AI_DONE).build();
    }
}
