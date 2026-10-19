package com.kama.jchatmind.execution;

import java.time.Instant;
import java.util.List;

/**
 * 运行读取 API 的响应投影。
 *
 * <p>账号接口返回 {@link Summary}/{@link Detail}（含 agentId、配置哈希、模型目录 ID、工具与检索 ID，
 * 只对会话所有者开放）。游客与公开精选只使用 {@link PublicSummary}/{@link PublicDetail}：字段白名单
 * 与 04 的 {@code DemoCatalogService} 同一思路，内部标识（会话、触发消息、配置哈希、模型目录 ID、
 * toolCallId、retrievalId、eventId）不出现在公开响应里。</p>
 *
 * <p>所有时间都是 UTC ISO-8601；未发生的时间为 null。{@code interrupted} 表示数据库里仍是
 * {@code RUNNING}，但心跳已经过期（重启遗留），界面必须显示为“中断/未知”，不能显示为执行中。</p>
 */
public final class RunViews {

    private RunViews() {
    }

    public record Event(String eventId, int sequence, String type, String status, String startedAt,
                        long durationMs, String toolCallId, String toolName, String retrievalId,
                        Integer retryAttempt, String errorCode, String detail) {
    }

    public record Summary(String runId, int schemaVersion, String sessionId, String triggeringMessageId,
                          String answerMessageId, String agentId, String agentName, String agentConfigHash,
                          String modelCatalogId, String modelName, String status, boolean interrupted,
                          String submittedAt, String startedAt, String finishedAt, Long durationMs,
                          Long queueDurationMs, Integer inputTokens, Integer outputTokens, Integer totalTokens,
                          String errorCode, boolean traceIncomplete, String heartbeatAt) {
    }

    public record Detail(Summary summary, List<Event> events) {
    }

    public record RunList(List<Summary> runs) {
    }

    public record PublicEvent(int sequence, String type, String status, String startedAt, long durationMs,
                              Integer retryAttempt, String toolName, String detail) {
    }

    /**
     * 游客/公开精选的运行摘要（字段白名单）。
     *
     * <p>{@code triggeringMessageId} 与 {@code answerMessageId} 只在<b>访客自己的会话</b>上有值：刷新后要把
     * 没有答案的失败/取消/中断运行挂回它原来的问题，只能依赖服务端关联。公开只读示例仍为 {@code null}，
     * 不能凭这两个字段推断别人的会话结构。</p>
     */
    public record PublicSummary(String runId, String status, boolean interrupted, String agentName,
                                String modelName, String submittedAt, String startedAt, String finishedAt,
                                Long durationMs, Long queueDurationMs, Integer inputTokens,
                                Integer outputTokens, Integer totalTokens, String errorCode,
                                boolean traceIncomplete, String triggeringMessageId, String answerMessageId) {
    }

    public record PublicDetail(PublicSummary summary, List<PublicEvent> events) {
    }

    public record PublicRunList(List<PublicSummary> runs) {
    }

    static String iso(Instant value) {
        return value == null ? null : value.toString();
    }
}