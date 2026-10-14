package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.auth.ResourceAccess;
import com.kama.jchatmind.auth.AuthException;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.SseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
@Slf4j
public class ChatEventListener {

    private final JChatMindFactory jChatMindFactory;
    private final ResourceAccess access;
    private final SseService sse;

    @Async
    @EventListener
    public void handle(ChatEvent event) {
        try {
            JChatMind jChatMind = jChatMindFactory.createForSubmission(event.getAgentId(), event.getSessionId(),
                    event.getTriggeringMessageId(), event.getUserInput(), event.getHistorySnapshot(),
                    config -> access.validateRuntime(event.getActorId(), event.getActorAuthVersion(),
                            event.getActorSessionHash(), event.getSessionId(), config));
            jChatMind.run();
        } catch (Exception error) {
            log.warn("Account chat execution failed: session={}", event.getSessionId(), error);
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof AuthException) return;
            }
            try {
                access.requireExecutionActor(event.getActorId(), event.getActorAuthVersion(),
                        event.getActorSessionHash(), event.getSessionId(), event.getAgentId());
                sse.send(event.getSessionId(), SseMessage.builder().type(SseMessage.Type.AI_DONE)
                        .payload(SseMessage.Payload.builder().done(true).statusText("执行失败，请刷新后重试").build()).build());
            } catch (RuntimeException ignored) { }
        }
    }
}
