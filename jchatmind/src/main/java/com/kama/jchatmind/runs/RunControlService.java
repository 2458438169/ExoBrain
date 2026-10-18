package com.kama.jchatmind.runs;

import com.kama.jchatmind.auth.AuthException;
import com.kama.jchatmind.auth.ResourceAccess;
import com.kama.jchatmind.demo.DemoAccessPolicy;
import com.kama.jchatmind.demo.DemoException;
import com.kama.jchatmind.demo.DemoSessionRepository;
import com.kama.jchatmind.execution.RunOrigin;
import com.kama.jchatmind.execution.RunRepository;
import com.kama.jchatmind.execution.RunStatus;
import com.kama.jchatmind.execution.RunSummary;
import org.springframework.stereotype.Service;

/**
 * 取消：排队中原子终止，运行中只记录协作式停止请求。
 *
 * <p>授权与读取同一条规则：账号必须仍然能读这个会话（管理员不会因为角色获得他人会话的取消权限），
 * 游客必须是该访客自己的会话，且公开精选只读。取消不会撤回已经发出的模型/工具请求，
 * 因此返回文案只承诺“不再发起新的调用”。</p>
 */
@Service
public class RunControlService {

    /**
     * @param runId           运行 ID
     * @param status          取消后的持久状态（RUNNING 表示停止请求已记录、尚未到检查点）
     * @param cancelRequested 是否记录了协作式停止请求
     * @param message         面向用户的诚实说明
     */
    public record View(String runId, String status, boolean cancelRequested, String message) {
    }

    private final RunRepository runs;
    private final RunQueueRepository jobs;
    private final RunTerminalWriter writer;
    private final ResourceAccess access;
    private final DemoAccessPolicy demo;
    private final DemoSessionRepository demoSessions;

    public RunControlService(RunRepository runs, RunQueueRepository jobs, RunTerminalWriter writer,
                             ResourceAccess access, DemoAccessPolicy demo, DemoSessionRepository demoSessions) {
        this.runs = runs;
        this.jobs = jobs;
        this.writer = writer;
        this.access = access;
        this.demo = demo;
        this.demoSessions = demoSessions;
    }

    public View cancelForAccount(String runId) {
        RunSummary run = runs.findById(runId);
        if (run == null || run.origin() != RunOrigin.ACCOUNT) {
            throw AuthException.missing();
        }
        access.requireSession(run.sessionId());
        return cancel(run);
    }

    public View cancelForDemo(String owner, String runId) {
        RunSummary run = runs.findById(runId);
        if (run == null || run.origin() != RunOrigin.DEMO) {
            throw new DemoException(404, "执行记录不存在");
        }
        if (demo.isShowcase(run.sessionId())) {
            throw new DemoException(403, "精选示例为只读记录");
        }
        if (owner == null || demoSessions.find(run.sessionId(), demo.agentId(), owner) == null) {
            throw new DemoException(404, "执行记录不存在");
        }
        return cancel(run);
    }

    private View cancel(RunSummary run) {
        RunTerminalWriter.CancelOutcome outcome = writer.cancel(run.runId());
        if (outcome == RunTerminalWriter.CancelOutcome.NOT_FOUND) {
            throw AuthException.missing();
        }
        RunSummary current = runs.findById(run.runId());
        String status = current == null ? run.status().name() : current.status().name();
        return switch (outcome) {
            case CANCELLED_WHILE_QUEUED -> new View(run.runId(), status, false, "已取消排队中的回答");
            case CANCEL_REQUESTED -> new View(run.runId(), status, true,
                    "已请求停止：后续检查点不再发起新的工具调用；已经发出的外部请求无法撤回");
            case ALREADY_TERMINAL -> new View(run.runId(), status, false, "本次回答已经结束");
            case NOT_FOUND -> throw AuthException.missing();
        };
    }

    /** 便于把持久状态转成公开字符串（不暴露任务表字段）。 */
    static String publicStatus(RunStatus status) {
        return status == null ? null : status.name();
    }
}
