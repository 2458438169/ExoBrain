package com.kama.jchatmind.model.request;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateChatMessageRequest {
    private String agentId;
    private String sessionId;
    private ChatMessageDTO.RoleType role;
    private String content;
    private ChatMessageDTO.MetaData metadata;
    /**
     * 07：客户端每次“新的提交意图”生成的幂等键，网络重试时必须复用同一个值。
     * 服务端以 {@code (actor, session, key)} 唯一约束去重；为空时服务端生成一次性键（不做重试去重）。
     */
    private String idempotencyKey;
}
