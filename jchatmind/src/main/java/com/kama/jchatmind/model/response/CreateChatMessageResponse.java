package com.kama.jchatmind.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateChatMessageResponse {
    private String chatMessageId;
    /**
     * 07：本次提交对应的运行记录。返回“已受理”时，用户消息、固定快照与排队任务已经在同一事务里落库；
     * 执行由数据库队列领取，不依赖这一次请求的进程。
     */
    private String runId;
    /** 提交后的任务状态（通常是 QUEUED）。 */
    private String runStatus;
    /** true 表示这是同一幂等键的重复提交，返回的是原来的 message/run，没有再向模型发送第二遍。 */
    private Boolean idempotentReplay;
}
