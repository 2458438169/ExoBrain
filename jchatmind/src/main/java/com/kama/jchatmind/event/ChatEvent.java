package com.kama.jchatmind.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 一次已受理对话提交的唤醒通知。
 *
 * <p>07 起这个事件<b>只用于唤醒 worker</b>，不再携带凭据、历史快照或执行所需的身份细节：
 * 用户消息、固定快照与排队任务已经在提交事务里写入数据库，真实执行从 {@code exobrain_chat_run_job}
 * 领取。事件丢失（进程崩溃/重启）不会丢失任务，轮询会继续；反过来，事件也不能让 worker 跳过
 * 数据库里的身份与配置校验。</p>
 */
@Getter
@AllArgsConstructor
public class ChatEvent {
    private final String runId;
    private final String sessionId;
    private final String origin;
}
