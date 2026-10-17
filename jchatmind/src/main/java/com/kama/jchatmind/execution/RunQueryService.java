package com.kama.jchatmind.execution;

import com.kama.jchatmind.auth.AuthException;
import com.kama.jchatmind.auth.ResourceAccess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 受权读取运行记录。每条路径都重新校验会话所有权（不依赖不可猜的 UUID）：
 *
 * <ul>
 *   <li>账号：复用现有 {@link ResourceAccess#requireSession(String)}，管理员不会因为角色而获得
 *       他人会话的运行记录，游客会话的运行也不会出现在账号接口里（origin 过滤）。</li>
 *   <li>游客：由 {@link DemoRunService} 先确认访客 Cookie 所有权或公开精选，再使用这里的白名单投影。</li>
 * </ul>
 */
@Service
public class RunQueryService {

    private final RunRepository runs;
    private final ResourceAccess access;
    private final long staleRunSeconds;

    public RunQueryService(RunRepository runs, ResourceAccess access,
                           @Value("${app.execution.stale-run-seconds:300}") long staleRunSeconds) {
        this.runs = runs;
        this.access = access;
        this.staleRunSeconds = staleRunSeconds;
    }

    public RunViews.RunList listForAccountSession(String sessionId) {
        access.requireSession(sessionId);
        return list(sessionId, RunOrigin.ACCOUNT);
    }

    public RunViews.Detail findForAccount(String runId) {
        RunSummary run = requireRun(runId);
        access.requireSession(run.sessionId());
        if (run.origin() != RunOrigin.ACCOUNT) {
            throw AuthException.missing();
        }
        return detail(run);
    }

    RunViews.RunList list(String sessionId, RunOrigin origin) {
        Instant now = Instant.now();
        List<RunViews.Summary> views = runs.findBySessionId(sessionId, origin).stream()
                .map(run -> summary(run, now)).toList();
        return new RunViews.RunList(views);
    }

    public RunViews.Detail detail(RunSummary run) {
        RunViews.Summary summary = summary(run, Instant.now());
        List<RunViews.Event> events = runs.findEvents(run.runId()).stream().map(RunQueryService::event).toList();
        return new RunViews.Detail(summary, events);
    }

    RunViews.PublicRunList listPublic(String sessionId) {
        Instant now = Instant.now();
        List<RunViews.PublicSummary> views = runs.findBySessionId(sessionId, RunOrigin.DEMO).stream()
                .map(run -> publicSummary(run, now)).toList();
        return new RunViews.PublicRunList(views);
    }

    RunViews.PublicDetail detailPublic(RunSummary run) {
        RunViews.PublicSummary summary = publicSummary(run, Instant.now());
        List<RunViews.PublicEvent> events = runs.findEvents(run.runId()).stream()
                .map(RunQueryService::publicEvent).toList();
        return new RunViews.PublicDetail(summary, events);
    }

    public RunSummary requireRun(String runId) {
        RunSummary run = runs.findById(runId);
        if (run == null) {
            throw AuthException.missing();
        }
        return run;
    }

    RunViews.Summary summary(RunSummary run, Instant now) {
        boolean interrupted = interrupted(run, now);
        return new RunViews.Summary(run.runId(), run.schemaVersion(), run.sessionId(), run.triggeringMessageId(),
                run.answerMessageId(), run.agentId(), run.agentNameAtStart(), run.agentConfigHash(),
                run.modelCatalogId(), run.modelNameAtStart(),
                interrupted ? RunStatus.INTERRUPTED.name() : run.status().name(), interrupted,
                RunViews.iso(run.submittedAt()), RunViews.iso(run.startedAt()), RunViews.iso(run.finishedAt()),
                run.durationMs(), run.queueDurationMs(), run.inputTokens(), run.outputTokens(),
                run.totalTokens(), run.errorCode(), run.traceIncomplete(), RunViews.iso(run.heartbeatAt()));
    }

    RunViews.PublicSummary publicSummary(RunSummary run, Instant now) {
        boolean interrupted = interrupted(run, now);
        return new RunViews.PublicSummary(run.runId(),
                interrupted ? RunStatus.INTERRUPTED.name() : run.status().name(), interrupted,
                run.agentNameAtStart(), run.modelNameAtStart(), RunViews.iso(run.submittedAt()),
                RunViews.iso(run.startedAt()), RunViews.iso(run.finishedAt()), run.durationMs(),
                run.queueDurationMs(), run.inputTokens(), run.outputTokens(), run.totalTokens(),
                run.errorCode(), run.traceIncomplete());
    }

    /**
     * 没有可靠领取租约时，重启遗留的 RUNNING 只能呈现为“中断/未知”：心跳超过阈值就不再显示为执行中。
     * 真正的恢复（领取、租约、重放）由任务 07 负责。
     */
    boolean interrupted(RunSummary run, Instant now) {
        return run.status() == RunStatus.RUNNING && run.heartbeatAt() != null
                && run.heartbeatAt().plusSeconds(staleRunSeconds).isBefore(now);
    }

    private static RunViews.Event event(RunEventRecord event) {
        return new RunViews.Event(event.eventId(), event.sequence(), event.type().name(), event.status().name(),
                RunViews.iso(event.startedAt()), event.durationMs(), event.toolCallId(), event.toolName(),
                event.retrievalId(), event.retryAttempt(), event.errorCode(), event.detail());
    }

    private static RunViews.PublicEvent publicEvent(RunEventRecord event) {
        return new RunViews.PublicEvent(event.sequence(), event.type().name(), event.status().name(),
                RunViews.iso(event.startedAt()), event.durationMs(), event.retryAttempt(), event.toolName(),
                event.detail());
    }
}