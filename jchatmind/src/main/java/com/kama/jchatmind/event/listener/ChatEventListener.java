package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.runs.RunWorker;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 提交后的唤醒：只让 worker 立即尝试领取一次。
 *
 * <p>这个监听器<b>不执行</b>任何对话：执行由 {@link RunWorker} 从数据库队列领取后进行。
 * 因此这里既不需要请求身份，也不承担“事件丢了任务就丢”的风险——数据库队列才是可靠来源。</p>
 */
@Component
@AllArgsConstructor
@Slf4j
public class ChatEventListener {

    private final RunWorker worker;

    @Async
    @EventListener
    public void handle(ChatEvent event) {
        log.debug("Waking the durable run worker: run={} session={} origin={}",
                event.getRunId(), event.getSessionId(), event.getOrigin());
        worker.wake();
    }
}
