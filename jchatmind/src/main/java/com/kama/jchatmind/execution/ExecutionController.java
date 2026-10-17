package com.kama.jchatmind.execution;

import com.kama.jchatmind.model.common.ApiResponse;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 账号读取运行记录：每条路径重新校验会话所有权（见 {@link RunQueryService}）。 */
@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ExecutionController {

    private final RunQueryService query;

    @GetMapping("/chat-sessions/{sessionId}/runs")
    public ApiResponse<RunViews.RunList> runs(@PathVariable String sessionId) {
        return ApiResponse.success(query.listForAccountSession(sessionId));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<RunViews.Detail> run(@PathVariable String runId) {
        return ApiResponse.success(query.findForAccount(runId));
    }
}