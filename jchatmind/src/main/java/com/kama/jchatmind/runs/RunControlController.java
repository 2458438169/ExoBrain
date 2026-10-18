package com.kama.jchatmind.runs;

import com.kama.jchatmind.demo.DemoVisitor;
import com.kama.jchatmind.model.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 取消运行的最小 API：账号走已授权会话，游客走访客 Cookie 所有权。
 *
 * <p>路径比 {@code DemoController} 的 {@code /api/demo/**} 兜底更具体，因此不会被当作“未发布的管理操作”。
 * 新增的游客路径需要 08 同步网关白名单（见 deliveries/07.md）。</p>
 */
@RestController
@AllArgsConstructor
public class RunControlController {

    private final RunControlService control;
    private final DemoVisitor visitors;

    @PostMapping("/api/runs/{runId}/cancel")
    public ApiResponse<RunControlService.View> cancelRun(@PathVariable String runId) {
        return ApiResponse.success(control.cancelForAccount(runId));
    }

    @PostMapping("/api/demo/runs/{runId}/cancel")
    public ApiResponse<RunControlService.View> cancelDemoRun(@PathVariable String runId,
            HttpServletRequest request, HttpServletResponse response) {
        return ApiResponse.success(control.cancelForDemo(visitors.owner(request, response), runId));
    }
}
