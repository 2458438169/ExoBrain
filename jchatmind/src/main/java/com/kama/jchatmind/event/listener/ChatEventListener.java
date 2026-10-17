package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.agent.AgentState;
import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.auth.ResourceAccess;
import com.kama.jchatmind.auth.AuthException;
import com.kama.jchatmind.execution.RunLifecycleService;
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
    private final RunLifecycleService runs;

    @Async
    @EventListener
    public void handle(ChatEvent event) {
        // 主记录创建失败不能显示为成功启动：本次不执行，只发一条明确的失败提示。
        RunLifecycleService.RunStart run;
        try {
            run = runs.startAccountRun(event.getSessionId(), event.getTriggeringMessageId(), event.getAgentId());
        } catch (RuntimeException unavailable) {
            log.error("Run record could not be created; the chat execution was not started: session={}",
                    event.getSessionId());
            notifyFailure(event, "执行记录创建失败，本次请求未执行");
            return;
        }
        try {
            JChatMind jChatMind = jChatMindFactory.createForSubmission(event.getAgentId(), event.getSessionId(),
                    event.getTriggeringMessageId(), event.getUserInput(), event.getHistorySnapshot(),
                    config -> access.validateRuntime(event.getActorId(), event.getActorAuthVersion(),
                            event.getActorSessionHash(), event.getSessionId(), config));
            jChatMind.attachRun(run.recorder());
            jChatMind.run();
            // 流程跑完但没有产出面向用户的答复（例如连续两轮都没有内容）不能算成功结束。
            if (jChatMind.state() == AgentState.ERROR) {
                runs.failed(run, "NO_ANSWER_PRODUCED");
            } else {
                runs.succeeded(run);
            }
        } catch (Exception error) {
            runs.failed(run, RunLifecycleService.classify(error));
            log.warn("Account chat execution failed: session={}", event.getSessionId(), error);
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof AuthException) return;
            }
            notifyFailure(event, "执行失败，请刷新后重试");
        }
    }

    /** 失败通知同样要重新校验身份；撤销的会话连失败事件都不发。 */
    private void notifyFailure(ChatEvent event, String statusText) {
        try {
            access.requireExecutionActor(event.getActorId(), event.getActorAuthVersion(),
                    event.getActorSessionHash(), event.getSessionId(), event.getAgentId());
            sse.send(event.getSessionId(), SseMessage.builder().type(SseMessage.Type.AI_DONE)
                    .payload(SseMessage.Payload.builder().done(true).statusText(statusText).build()).build());
        } catch (RuntimeException ignored) { }
    }
}
