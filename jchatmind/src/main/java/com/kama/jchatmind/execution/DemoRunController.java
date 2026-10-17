package com.kama.jchatmind.execution;

import com.kama.jchatmind.demo.DemoException;
import com.kama.jchatmind.demo.DemoVisitor;
import com.kama.jchatmind.model.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 游客读取运行记录（需要 08 把这两条精确路径加入 Nginx 白名单）。
 *
 * <p>路径比 {@code DemoController} 的 {@code /api/demo/**} 兜底更具体，因此不会被兜底拦截；
 * 错误响应沿用公开体验的格式（code/message/data），不暴露内部细节。</p>
 */
@RestController
@AllArgsConstructor
public class DemoRunController {

    private final DemoRunService runs;
    private final DemoVisitor visitors;

    @GetMapping("/api/demo/chat-sessions/{sessionId}/runs")
    public ApiResponse<RunViews.PublicRunList> runs(@PathVariable String sessionId,
                                                    HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.success(runs.list(visitors.owner(request, response), sessionId));
    }

    @GetMapping("/api/demo/runs/{runId}")
    public ApiResponse<RunViews.PublicDetail> run(@PathVariable String runId,
                                                  HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.success(runs.find(visitors.owner(request, response), runId));
    }

    /** 未发布/无权访问的运行一律 404，与 DemoController 的错误格式保持一致。 */
    @ExceptionHandler(DemoException.class)
    public ResponseEntity<Map<String, Object>> error(DemoException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.getStatus());
        body.put("message", error.getMessage());
        body.put("data", null);
        return ResponseEntity.status(error.getStatus()).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}