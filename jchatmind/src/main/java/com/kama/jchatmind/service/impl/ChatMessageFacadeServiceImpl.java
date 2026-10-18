package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.auth.AuthContext;
import com.kama.jchatmind.auth.AuthException;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.execution.RunOrigin;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.request.UpdateChatMessageRequest;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.response.GetChatMessagesResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.runs.RunSubmissionException;
import com.kama.jchatmind.runs.RunSubmissionService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 聊天消息的持久化入口。
 *
 * <p>07 起“提交用户消息”不再在请求线程上启动执行：用户消息、固定的历史快照、运行摘要（QUEUED）与
 * 排队任务由 {@link RunSubmissionService} 在同一个事务里写入，成功返回即代表已经原子落库；
 * 真实执行由数据库队列在 worker 上领取。重复提交（同一幂等键）返回原来的 message/run，
 * 不会向模型发送第二遍。</p>
 */
@Service
@AllArgsConstructor
public class ChatMessageFacadeServiceImpl implements ChatMessageFacadeService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageFacadeServiceImpl.class);

    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageConverter chatMessageConverter;
    private final ObjectMapper json;
    private final RunSubmissionService submissions;

    @Override
    public GetChatMessagesResponse getChatMessagesBySessionId(String sessionId) {
        List<ChatMessage> chatMessages = chatMessageMapper.selectBySessionId(sessionId);
        List<ChatMessageVO> result = new ArrayList<>();

        for (ChatMessage chatMessage : chatMessages) {
            try {
                ChatMessageVO vo = chatMessageConverter.toVO(chatMessage);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

        return GetChatMessagesResponse.builder()
                .chatMessages(result.toArray(new ChatMessageVO[0]))
                .build();
    }

    @Override
    public List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit) {
        List<ChatMessage> chatMessages = chatMessageMapper.selectBySessionIdRecently(sessionId, limit);
        List<ChatMessageDTO> result = new ArrayList<>();
        for (ChatMessage chatMessage : chatMessages) {
            try {
                ChatMessageDTO dto = chatMessageConverter.toDTO(chatMessage);
                result.add(dto);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return result;
    }

    @Override
    public CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request) {
        var identity = AuthContext.requireSession();
        try {
            return submissions.submitAccount(identity.user().id(), identity.authVersion(), identity.tokenHash(),
                    request.getAgentId(), request.getSessionId(), request.getContent(),
                    request.getIdempotencyKey()).toResponse();
        } catch (RunSubmissionException rejected) {
            throw new AuthException(rejected.status(), rejected.getMessage());
        } catch (DataIntegrityViolationException concurrentConflict) {
            // 并发重复提交/同会话并发提交：整个事务已回滚（消息没有写入），这里返回原来的 message/run
            // 或明确的冲突，而不是留下半条记录。
            log.debug("Concurrent chat submission conflict; resolving through the idempotency key");
            try {
                return submissions.replayAfterConflict(RunOrigin.ACCOUNT,
                        RunSubmissionService.actorScope(RunOrigin.ACCOUNT, identity.user().id()),
                        request.getSessionId(), request.getIdempotencyKey(),
                        RunSubmissionService.requestDigest(RunOrigin.ACCOUNT, request.getSessionId(),
                                request.getAgentId(), request.getContent())).toResponse();
            } catch (RunSubmissionException conflict) {
                throw new AuthException(conflict.status(), conflict.getMessage());
            }
        }
    }

    @Override
    public CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO) {
        ChatMessage chatMessage = doCreateChatMessage(chatMessageDTO);
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    @Override
    public CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request) {
        ChatMessage chatMessage = doCreateChatMessage(request);
        // 和 createChatMessage 的区别在于，Agent 创建的 chatMessage 不需要发布事件
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    private ChatMessage doCreateChatMessage(CreateChatMessageRequest request) {
        // 将 CreateChatMessageRequest 转换为 ChatMessageDTO
        ChatMessageDTO chatMessageDTO = chatMessageConverter.toDTO(request);
        // 将 ChatMessageDTO 转换为 ChatMessage 实体
        return doCreateChatMessage(chatMessageDTO);
    }

    private ChatMessage doCreateChatMessage(ChatMessageDTO chatMessageDTO) {
        try {
            // 将 ChatMessageDTO 转换为 ChatMessage 实体
            ChatMessage chatMessage = chatMessageConverter.toEntity(chatMessageDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            chatMessage.setCreatedAt(now);
            chatMessage.setUpdatedAt(now);
            // 插入数据库，ID 由数据库自动生成
            int result = chatMessageMapper.insert(chatMessage);
            if (result <= 0) {
                throw new BizException("创建聊天消息失败");
            }
            return chatMessage;
        } catch (JsonProcessingException e) {
            throw new BizException("创建聊天消息时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent) {
        // 查询现有的聊天消息
        ChatMessage existingChatMessage = chatMessageMapper.selectById(chatMessageId);
        if (existingChatMessage == null) {
            throw new BizException("聊天消息不存在: " + chatMessageId);
        }

        // 将追加内容添加到现有内容后面
        String currentContent = existingChatMessage.getContent() != null
                ? existingChatMessage.getContent()
                : "";
        String updatedContent = currentContent + appendContent;

        // 创建更新后的消息对象
        ChatMessage updatedChatMessage = ChatMessage.builder()
                .id(existingChatMessage.getId())
                .sessionId(existingChatMessage.getSessionId())
                .role(existingChatMessage.getRole())
                .content(updatedContent)
                .metadata(existingChatMessage.getMetadata())
                .createdAt(existingChatMessage.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();

        // 更新数据库
        int result = chatMessageMapper.updateById(updatedChatMessage);
        if (result <= 0) {
            throw new BizException("追加聊天消息内容失败");
        }

        // 返回聊天消息ID
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessageId)
                .build();
    }

    @Override
    public void deleteChatMessage(String chatMessageId) {
        ChatMessage chatMessage = chatMessageMapper.selectById(chatMessageId);
        if (chatMessage == null) {
            throw new BizException("聊天消息不存在: " + chatMessageId);
        }

        int result = chatMessageMapper.deleteById(chatMessageId);
        if (result <= 0) {
            throw new BizException("删除聊天消息失败");
        }
    }

    @Override
    public void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request) {
        try {
            // 查询现有的聊天消息
            ChatMessage existingChatMessage = chatMessageMapper.selectById(chatMessageId);
            if (existingChatMessage == null) {
                throw new BizException("聊天消息不存在: " + chatMessageId);
            }

            // 将现有 ChatMessage 转换为 ChatMessageDTO
            ChatMessageDTO chatMessageDTO = chatMessageConverter.toDTO(existingChatMessage);

            // 使用 UpdateChatMessageRequest 更新 ChatMessageDTO
            chatMessageConverter.updateDTOFromRequest(chatMessageDTO, request);

            // 将更新后的 ChatMessageDTO 转换回 ChatMessage 实体
            ChatMessage updatedChatMessage = chatMessageConverter.toEntity(chatMessageDTO);

            // 保留原有的 ID、sessionId、role 和创建时间
            updatedChatMessage.setId(existingChatMessage.getId());
            updatedChatMessage.setSessionId(existingChatMessage.getSessionId());
            updatedChatMessage.setRole(existingChatMessage.getRole());
            updatedChatMessage.setCreatedAt(existingChatMessage.getCreatedAt());
            updatedChatMessage.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = chatMessageMapper.updateById(updatedChatMessage);
            if (result <= 0) {
                throw new BizException("更新聊天消息失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新聊天消息时发生序列化错误: " + e.getMessage());
        }
    }
}
