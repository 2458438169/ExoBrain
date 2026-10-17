package com.kama.jchatmind.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 运行状态机、用量与事件写入失败的边界（不依赖数据库）。 */
class RunModelTest {

    @Test
    @DisplayName("终态不能被迟到的更新覆盖，重复终态幂等")
    void terminalStatesCannotBeOverwritten() {
        assertTrue(RunStatus.RUNNING.canTransitionTo(RunStatus.SUCCEEDED));
        assertTrue(RunStatus.RUNNING.canTransitionTo(RunStatus.INTERRUPTED));
        assertTrue(RunStatus.QUEUED.canTransitionTo(RunStatus.RUNNING));
        assertTrue(RunStatus.SUCCEEDED.canTransitionTo(RunStatus.SUCCEEDED), "duplicate terminal write is idempotent");
        assertFalse(RunStatus.SUCCEEDED.canTransitionTo(RunStatus.RUNNING), "a late RUNNING must never win");
        assertFalse(RunStatus.SUCCEEDED.canTransitionTo(RunStatus.FAILED));
        assertFalse(RunStatus.INTERRUPTED.canTransitionTo(RunStatus.SUCCEEDED));
        assertThrows(IllegalStateException.class, () -> summary(RunStatus.SUCCEEDED)
                .withStatus(RunStatus.RUNNING, Instant.now(), 1L, null, TokenUsage.UNKNOWN, false));
    }

    @Test
    @DisplayName("用量只接受服务商提供的数字，全 0/全空视为未提供")
    void usageIsNeverFabricated() {
        assertEquals(TokenUsage.UNKNOWN, TokenUsage.of(0, 0, 0));
        assertEquals(TokenUsage.UNKNOWN, TokenUsage.of(null, null, null));
        assertEquals(new TokenUsage(10, 4, 14), TokenUsage.of(10, 4, null));
        assertEquals(new TokenUsage(10, 4, 14), TokenUsage.of(10, 4, 14));
        assertEquals(new TokenUsage(20, 8, 28), TokenUsage.of(10, 4, null).plus(TokenUsage.of(10, 4, null)));
        assertEquals(new TokenUsage(10, 0, 10), TokenUsage.of(10, 0, null),
                "a reported field keeps the provider's value; only an all-zero/empty usage is unknown");
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(-1, null, null));
    }

    @Test
    @DisplayName("事件写入失败时保留已有记录、标记不完整且不中断执行")
    void failingSinkMarksTheTraceIncomplete() {
        List<RunEventRecord> accepted = new ArrayList<>();
        boolean[] failNext = {false};
        RunEventSink sink = new RunEventSink() {
            @Override
            public void append(RunEventRecord event) {
                if (failNext[0]) {
                    throw new IllegalStateException("database unavailable");
                }
                accepted.add(event);
            }

            @Override
            public void touch(String runId) {
            }

            @Override
            public void setAnswerMessageId(String runId, String answerMessageId) {
                throw new IllegalStateException("database unavailable");
            }
        };
        RunRecorder recorder = new RunRecorder("33333333-3333-4333-8333-333333333333", sink, System.nanoTime());

        recorder.note(RunEventType.MODEL_CALL, RunEventStatus.SUCCEEDED, null, null);
        failNext[0] = true;
        recorder.note(RunEventType.TOOL_CALL, RunEventStatus.SUCCEEDED, null, null);
        recorder.answerMessageId("44444444-4444-4444-8444-444444444444");
        failNext[0] = false;
        recorder.note(RunEventType.PERSIST_MESSAGE, RunEventStatus.SUCCEEDED, null, null);

        assertTrue(recorder.traceIncomplete(), "a failed write must be reported as an incomplete trace");
        assertEquals(2, accepted.size(), "already written events stay; the failed one is not re-invented");
        assertEquals(List.of(1, 3), accepted.stream().map(RunEventRecord::sequence).toList(),
                "sequence numbers are consumed even when a write fails");
        assertFalse(accepted.stream().anyMatch(e -> e.type() == RunEventType.TOOL_CALL));
        assertNull(recorder.usage().totalTokens());
    }

    private static RunSummary summary(RunStatus status) {
        Instant now = Instant.now();
        return new RunSummary(RunSummary.SCHEMA_VERSION, "33333333-3333-4333-8333-333333333333",
                "22222222-2222-4222-8222-222222222222", null, null, "助手", null, null, null,
                status, now, now, now, 1L, 0L, null, null, null, null, null, false, now, RunOrigin.ACCOUNT);
    }
}