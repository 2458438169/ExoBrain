package com.kama.jchatmind.execution;

import com.kama.jchatmind.demo.DemoCatalogService;
import com.kama.jchatmind.demo.DemoChatService;
import com.kama.jchatmind.demo.DemoController;
import com.kama.jchatmind.demo.DemoException;
import com.kama.jchatmind.demo.DemoVisitor;
import com.kama.jchatmind.runs.RunControlController;
import com.kama.jchatmind.runs.RunControlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 游客运行路由必须比 {@code DemoController} 的 {@code /api/demo/**} 兜底更具体，
 * 否则新接口会被兜底当成“不支持的管理操作”返回 403。
 */
class DemoRunRoutingTest {

    private static final String RUN = "33333333-3333-4333-8333-333333333333";
    private static final String SESSION = "22222222-2222-4222-8222-222222222222";

    @Test
    @DisplayName("游客运行读取路由优先于 /api/demo/** 兜底")
    void guestRunRoutesWinOverTheCatchAll() throws Exception {
        DemoRunService runs = mock(DemoRunService.class);
        when(runs.find(anyString(), anyString())).thenThrow(new DemoException(404, "执行记录不存在"));
        when(runs.list(anyString(), anyString())).thenThrow(new DemoException(404, "执行记录不存在"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(demoController(),
                new DemoRunController(runs, new DemoVisitor())).build();

        mvc.perform(get("/api/demo/runs/" + RUN)).andExpect(status().isNotFound());
        mvc.perform(get("/api/demo/chat-sessions/" + SESSION + "/runs")).andExpect(status().isNotFound());
        // 其余未发布的 demo 路径仍然被兜底拒绝（403），没有顺带放开整个前缀。
        mvc.perform(get("/api/demo/agents/x")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("游客取消只接受 POST；GET 同一路径不会产生取消副作用")
    void cancelRouteAcceptsPostOnly() throws Exception {
        RunControlService control = mock(RunControlService.class);
        when(control.cancelForDemo(any(), anyString()))
                .thenReturn(new RunControlService.View(RUN, "RUNNING", true, "已请求停止"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(demoController(),
                new RunControlController(control, new DemoVisitor())).build();

        mvc.perform(post("/api/demo/runs/" + RUN + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelRequested").value(true));
        // 后端只有 POST 映射：GET 落到 /api/demo/** 兜底被拒（403），取消逻辑一次都没跑；生产网关更早返回 405。
        mvc.perform(get("/api/demo/runs/" + RUN + "/cancel")).andExpect(status().isForbidden());
        verify(control, times(1)).cancelForDemo(any(), anyString());
    }

    @Test
    @DisplayName("账号运行读取路径需要会话校验（未登录时由鉴权层拒绝，这里验证映射存在）")
    void accountRunRoutesAreMapped() throws Exception {
        RunQueryService query = mock(RunQueryService.class);
        when(query.listForAccountSession(anyString()))
                .thenReturn(new RunViews.RunList(List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExecutionController(query)).build();
        mvc.perform(get("/api/chat-sessions/" + SESSION + "/runs")).andExpect(status().isOk());
    }

    private static DemoController demoController() {
        return new DemoController(mock(DemoChatService.class), new DemoVisitor(), mock(DemoCatalogService.class));
    }
}
