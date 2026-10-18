package com.kama.jchatmind.demo;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.response.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@AllArgsConstructor
public class DemoController {
    private final DemoChatService demo;
    private final DemoVisitor visitors;
    private final DemoCatalogService catalog;

    public record SessionInput(String agentId, String title) { }
    public record TitleInput(String title) { }
    public record MessageInput(String agentId, String sessionId, String role, String content, String idempotencyKey) { }

    @GetMapping("/api/demo/agents")
    public ApiResponse<GetAgentsResponse> agents(HttpServletRequest request, HttpServletResponse response) {
        visitors.owner(request, response);
        return ApiResponse.success(demo.agents());
    }

    @GetMapping("/api/demo/chat-sessions")
    public ApiResponse<GetChatSessionsResponse> sessions(HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.success(demo.sessions(visitors.owner(request, response)));
    }

    @GetMapping("/api/demo/chat-sessions/agent/{agentId}")
    public ApiResponse<GetChatSessionsResponse> sessionsByAgent(@PathVariable String agentId,
            HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.success(demo.sessionsForAgent(visitors.owner(request, response), agentId));
    }

    @GetMapping("/api/demo/chat-sessions/{id}")
    public ApiResponse<GetChatSessionResponse> session(@PathVariable String id,
            HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.success(demo.session(visitors.owner(request, response), id));
    }

    @PostMapping("/api/demo/chat-sessions")
    public ApiResponse<CreateChatSessionResponse> create(@RequestBody SessionInput input,
            HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.success(demo.create(visitors.owner(request, response), input.agentId(), input.title()));
    }

    @PatchMapping("/api/demo/chat-sessions/{id}")
    public ApiResponse<Void> rename(@PathVariable String id, @RequestBody TitleInput input,
            HttpServletRequest request, HttpServletResponse response) {
        demo.rename(visitors.owner(request, response), id, input.title());
        return ApiResponse.success();
    }

    @DeleteMapping("/api/demo/chat-sessions/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        demo.delete(visitors.owner(request, response), id);
        return ApiResponse.success();
    }

    @GetMapping("/api/demo/chat-messages/session/{id}")
    public ApiResponse<GetChatMessagesResponse> messages(@PathVariable String id,
            HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.success(demo.messages(visitors.owner(request, response), id));
    }

    @PostMapping("/api/demo/chat-messages")
    public ApiResponse<CreateChatMessageResponse> send(@RequestBody MessageInput input,
            HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.success(demo.send(visitors.owner(request, response), input.agentId(), input.sessionId(),
                input.role(), input.content(), input.idempotencyKey()));
    }

    @GetMapping(value = "/sse/demo/connect/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        return demo.connect(visitors.owner(request, response), id);
    }

    @GetMapping("/api/demo/knowledge-bases")
    public ApiResponse<GetKnowledgeBasesResponse> knowledgeBases(HttpServletRequest request, HttpServletResponse response) {
        visitors.owner(request, response);
        return ApiResponse.success(catalog.knowledgeBases());
    }

    @GetMapping("/api/demo/documents/kb/{id}")
    public ApiResponse<GetDocumentsResponse> documents(@PathVariable String id,
            HttpServletRequest request, HttpServletResponse response) {
        visitors.owner(request, response);
        return ApiResponse.success(catalog.documents(id));
    }

    @GetMapping("/api/demo/documents/{documentId}/content")
    public ApiResponse<GetDocumentContentResponse> documentContent(@PathVariable String documentId,
            HttpServletRequest request, HttpServletResponse response) {
        visitors.owner(request, response);
        return ApiResponse.success(catalog.documentContent(documentId));
    }

    @GetMapping("/api/demo/tools")
    public ApiResponse<List<DemoCatalogService.PublicTool>> tools(HttpServletRequest request, HttpServletResponse response) {
        visitors.owner(request, response);
        return ApiResponse.success(catalog.tools());
    }

    @GetMapping("/api/demo/showcase")
    public ApiResponse<DemoCatalogService.Showcases> showcases(HttpServletRequest request, HttpServletResponse response) {
        visitors.owner(request, response);
        return ApiResponse.success(catalog.showcases());
    }

    @GetMapping("/api/demo/showcase/{id}")
    public ApiResponse<DemoCatalogService.Showcase> showcase(@PathVariable String id,
            HttpServletRequest request, HttpServletResponse response) {
        visitors.owner(request, response);
        return ApiResponse.success(catalog.showcase(id));
    }

    @RequestMapping({"/api/demo/**", "/sse/demo/**"})
    public void unsupported() {
        throw new DemoException(403, "公开体验不提供此管理操作");
    }

    @ExceptionHandler(DemoException.class)
    public ResponseEntity<Map<String, Object>> error(DemoException error) {
        return errorResponse(error.getStatus(), error.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> invalidInput() {
        return errorResponse(400, "请求格式无效");
    }

    private ResponseEntity<Map<String, Object>> errorResponse(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status);
        body.put("message", message);
        body.put("data", null);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
