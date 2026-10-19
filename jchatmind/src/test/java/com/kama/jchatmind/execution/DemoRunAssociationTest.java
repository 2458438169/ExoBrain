package com.kama.jchatmind.execution;

import com.kama.jchatmind.auth.ResourceAccess;
import com.kama.jchatmind.demo.DemoAccessPolicy;
import com.kama.jchatmind.demo.DemoException;
import com.kama.jchatmind.demo.DemoProperties;
import com.kama.jchatmind.demo.DemoSessionRepository;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UX-01：游客自己会话的运行摘要要带触发/答案消息关联，公开只读示例仍然没有。
 *
 * <p>刷新后把没有答案的失败、取消、中断运行挂回原问题，只能依赖服务端关联；而关联字段一旦在所有
 * 游客响应里都出现，等于把别人会话的消息 ID 暴露出去。</p>
 */
class DemoRunAssociationTest {

    private static final String AGENT = "11111111-1111-4111-8111-111111111111";
    private static final String SESSION = "22222222-2222-4222-8222-222222222222";
    private static final String SHOWCASE = "44444444-4444-4444-8444-444444444444";
    private static final String RUN = "33333333-3333-4333-8333-333333333333";
    private static final String QUESTION = "55555555-5555-4555-8555-555555555555";
    private static final String ANSWER = "66666666-6666-4666-8666-666666666666";

    @Test
    @DisplayName("访客自己的运行带触发与答案消息关联")
    void ownRunCarriesTheMessageAssociation() {
        RunRepository runs = mock(RunRepository.class);
        when(runs.findBySessionId(SESSION, RunOrigin.DEMO)).thenReturn(List.of(run(SESSION)));
        when(runs.findById(RUN)).thenReturn(run(SESSION));
        when(runs.findEvents(RUN)).thenReturn(List.of());

        DemoSessionRepository sessions = mock(DemoSessionRepository.class);
        when(sessions.find(SESSION, AGENT, "owner-hash")).thenReturn(session(SESSION));

        DemoRunService service = new DemoRunService(policy(), sessions, query(runs));

        RunViews.PublicRunList list = service.list("owner-hash", SESSION);
        assertEquals(1, list.runs().size());
        assertEquals(QUESTION, list.runs().get(0).triggeringMessageId());
        assertEquals(ANSWER, list.runs().get(0).answerMessageId());

        RunViews.PublicDetail detail = service.find("owner-hash", RUN);
        assertEquals(QUESTION, detail.summary().triggeringMessageId());
        assertEquals(ANSWER, detail.summary().answerMessageId());
    }

    @Test
    @DisplayName("公开只读示例不返回关联字段")
    void showcaseRunKeepsTheRestrictedProjection() {
        RunRepository runs = mock(RunRepository.class);
        when(runs.findBySessionId(SHOWCASE, RunOrigin.DEMO)).thenReturn(List.of(run(SHOWCASE)));
        when(runs.findById(RUN)).thenReturn(run(SHOWCASE));
        when(runs.findEvents(RUN)).thenReturn(List.of());

        DemoSessionRepository sessions = mock(DemoSessionRepository.class);
        when(sessions.find(SHOWCASE, AGENT, "visitor-hash")).thenReturn(null);
        when(sessions.findShowcase(SHOWCASE, AGENT)).thenReturn(session(SHOWCASE));

        DemoRunService service = new DemoRunService(policy(), sessions, query(runs));

        RunViews.PublicRunList list = service.list("visitor-hash", SHOWCASE);
        assertEquals(1, list.runs().size());
        assertNull(list.runs().get(0).triggeringMessageId());
        assertNull(list.runs().get(0).answerMessageId());
        assertNull(service.find("visitor-hash", RUN).summary().triggeringMessageId());
    }

    @Test
    @DisplayName("第二个访客读不到别人的运行")
    void anotherVisitorCannotReadTheRun() {
        RunRepository runs = mock(RunRepository.class);
        when(runs.findById(RUN)).thenReturn(run(SESSION));
        DemoSessionRepository sessions = mock(DemoSessionRepository.class);
        when(sessions.find(SESSION, AGENT, "other-hash")).thenReturn(null);

        DemoRunService service = new DemoRunService(policy(), sessions, query(runs));
        DemoException error = assertThrows(DemoException.class, () -> service.find("other-hash", RUN));
        assertEquals(404, error.getStatus());
    }

    private static RunQueryService query(RunRepository runs) {
        return new RunQueryService(runs, mock(ResourceAccess.class), 300L);
    }

    private static DemoAccessPolicy policy() {
        DemoProperties properties = new DemoProperties();
        properties.setAgentId(AGENT);
        properties.setShowcaseSessionIds(List.of(SHOWCASE));
        return new DemoAccessPolicy(properties);
    }

    private static ChatSessionVO session(String id) {
        return ChatSessionVO.builder().id(id).agentId(AGENT).title("t").build();
    }

    private static RunSummary run(String sessionId) {
        return new RunSummary(RunSummary.SCHEMA_VERSION, RUN, sessionId, QUESTION, AGENT, "ExoBrain 演示助手",
                "hash", "deepseek-chat", "DeepSeek-V4.1-Flash", RunStatus.FAILED, Instant.now(), Instant.now(),
                Instant.now(), 1_200L, 30L, 10, 0, 10, "NO_ANSWER_PRODUCED", ANSWER, false, Instant.now(),
                RunOrigin.DEMO);
    }
}
