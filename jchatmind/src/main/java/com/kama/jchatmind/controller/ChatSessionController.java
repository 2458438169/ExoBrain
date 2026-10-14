package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.CreateChatSessionRequest;
import com.kama.jchatmind.model.request.UpdateChatSessionRequest;
import com.kama.jchatmind.model.response.CreateChatSessionResponse;
import com.kama.jchatmind.model.response.GetChatSessionResponse;
import com.kama.jchatmind.model.response.GetChatSessionsResponse;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.auth.ResourceAccess;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ChatSessionController {

    private final ChatSessionFacadeService chatSessionFacadeService;
    private final ResourceAccess access;

    // 查询所有聊天会话
    @GetMapping("/chat-sessions")
    public ApiResponse<GetChatSessionsResponse> getChatSessions() {
        return ApiResponse.success(visible(chatSessionFacadeService.getChatSessions()));
    }

    // 查询单个聊天会话
    @GetMapping("/chat-sessions/{chatSessionId}")
    public ApiResponse<GetChatSessionResponse> getChatSession(@PathVariable String chatSessionId) {
        access.requireSession(chatSessionId);
        return ApiResponse.success(chatSessionFacadeService.getChatSession(chatSessionId));
    }

    // 根据 agentId 查询聊天会话
    @GetMapping("/chat-sessions/agent/{agentId}")
    public ApiResponse<GetChatSessionsResponse> getChatSessionsByAgentId(@PathVariable String agentId) {
        access.requireAgent(agentId, false);
        return ApiResponse.success(visible(chatSessionFacadeService.getChatSessionsByAgentId(agentId)));
    }

    // 创建聊天会话
    @PostMapping("/chat-sessions")
    @Transactional
    public ApiResponse<CreateChatSessionResponse> createChatSession(@RequestBody CreateChatSessionRequest request) {
        access.validateNewSession(request.getAgentId(), request.getTitle());
        var response = chatSessionFacadeService.createChatSession(request);
        access.claim(ResourceAccess.SESSION, response.getChatSessionId());
        return ApiResponse.success(response);
    }

    // 删除聊天会话
    @DeleteMapping("/chat-sessions/{chatSessionId}")
    @Transactional
    public ApiResponse<Void> deleteChatSession(@PathVariable String chatSessionId) {
        access.requireSession(chatSessionId);
        chatSessionFacadeService.deleteChatSession(chatSessionId);
        access.remove(ResourceAccess.SESSION, chatSessionId);
        return ApiResponse.success();
    }

    // 更新聊天会话
    @PatchMapping("/chat-sessions/{chatSessionId}")
    public ApiResponse<Void> updateChatSession(@PathVariable String chatSessionId, @RequestBody UpdateChatSessionRequest request) {
        access.requireSession(chatSessionId);
        ResourceAccess.text(request.getTitle(), "对话标题", 200, true);
        chatSessionFacadeService.updateChatSession(chatSessionId, request);
        return ApiResponse.success();
    }

    private GetChatSessionsResponse visible(GetChatSessionsResponse response) {
        response.setChatSessions(Arrays.stream(response.getChatSessions())
                .filter(session -> access.canRead(ResourceAccess.SESSION, session.getId())).toArray(ChatSessionVO[]::new));
        return response;
    }
}
