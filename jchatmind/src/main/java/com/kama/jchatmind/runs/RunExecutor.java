package com.kama.jchatmind.runs;

import com.kama.jchatmind.agent.AgentState;
import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.auth.AuthException;
import com.kama.jchatmind.auth.ResourceAccess;
import com.kama.jchatmind.demo.DemoAccessPolicy;
import com.kama.jchatmind.demo.DemoExecutionService;
import com.kama.jchatmind.execution.RunEventRecord;
import com.kama.jchatmind.execution.RunEventSink;
import com.kama.jchatmind.execution.RunLifecycleService;
import com.kama.jchatmind.execution.RunOrigin;
import com.kama.jchatmind.execution.RunRecorder;
import com.kama.jchatmind.execution.RunRepository;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.service.SseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * 一次已领取运行的真实执行。
 *
 * <p>模型与工具调用全部在数据库事务之外；只有“领取/续租/终态”走短事务。每次开始执行前重新校验
 * 身份、会话归属、助手配置版本与模型可用性；执行期间每个检查点（{@link RunLease#checkpoint()}）
 * 续租、检查取消请求，并继续通过 {@link ResourceAccess#validateRuntime} /
 * {@link DemoAccessPolicy#validateAgent} 重新授权（工具调用由 {@code ScopedToolCallback} 再检查一次）。</p>
 *
 * <p>失败与中断的边界：租约丢失时<b>不写任何结果</b>；收到取消时协同停止，不撤回已经发出的外部请求；
 * 其它异常写入 {@code FAILED} + 固定错误码。这里不承诺 exactly-once：崩溃后已经发生的模型/工具调用
 * 不会回滚，运行会被诚实标记为 {@code INTERRUPTED}。</p>
 */
@Service
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    private final JChatMindFactory factory;
    private final DemoExecutionService demoRuntime;
    private final RunAuthorization authorization;
    private final ResourceAccess access;
    private final DemoAccessPolicy demo;
    private final SseService sse;
    private final RunQueueRepository jobs;
    private final RunRepository runs;
    private final RunResultWriter resultWriter;

    public RunExecutor(JChatMindFactory factory, DemoExecutionService demoRuntime, RunAuthorization authorization,
                       ResourceAccess access, DemoAccessPolicy demo, SseService sse, RunQueueRepository jobs,
                       RunRepository runs, RunResultWriter resultWriter) {
        this.factory = factory;
        this.demoRuntime = demoRuntime;
        this.authorization = authorization;
        this.access = access;
        this.demo = demo;
        this.sse = sse;
        this.jobs = jobs;
        this.runs = runs;
        this.resultWriter = resultWriter;
    }

    /** 执行一次已领取的运行；{@code lease} 由 worker 在领取后构造。 */
    public void execute(RunQueueJob job, RunLease lease) {
        RunRecorder recorder = new RunRecorder(job.runId(), sink(), RunRecorder.now());
        Instant startedAt = Instant.now();
        long queueDurationMs = job.createdAt() == null ? 0L
                : Math.max(0L, Duration.between(job.createdAt(), startedAt).toMillis());
        if (!jobs.startRun(job.runId(), startedAt, queueDurationMs)) {
            // 运行摘要已经不是 QUEUED（被取消/已完成/被级联删除）：不执行，也不覆盖既定状态。
            log.warn("Queued run could not be started because its run record is no longer QUEUED: run={}",
                    job.runId());
            lease.failed(recorder, "RUN_NOT_STARTABLE");
            return;
        }
        AgentDTO config;
        try {
            config = authorization.revalidate(job);
        } catch (RunRejectedException rejected) {
            log.warn("Queued run rejected before execution: run={} code={}", job.runId(), rejected.code());
            finishRejected(job, lease, recorder, rejected);
            return;
        }

        try {
            JChatMind runtime = job.origin() == RunOrigin.ACCOUNT
                    ? factory.createForSubmission(job.agentId(), job.sessionId(), job.triggeringMessageId(),
                            job.userInput(), job.historySnapshot(), accountGuard(job, lease))
                    : demoRuntime.runtime(job.agentId(), job.sessionId(), demoGuard(lease));
            runtime.attachRun(recorder);
            // 07 / F01：消息落库、终态与答案关联都带运行态与 fencing 校验；模型调用不在这些事务里。
            runtime.attachRunPersistence((message, finalAnswer) -> resultWriter.persistMessage(
                    job.id(), lease.fencingToken(), job.runId(), message, finalAnswer));
            runtime.run();
            if (runtime.state() == AgentState.ERROR) {
                lease.failed(recorder, "NO_ANSWER_PRODUCED");
            } else {
                lease.succeeded(recorder);
            }
        } catch (RuntimeException error) {
            RunCancelledException cancelled = find(error, RunCancelledException.class);
            if (cancelled != null) {
                // 协作式停止：检查点之后不再发起新的工具调用。已经发出的外部请求不会被撤回。
                lease.cancelledRun(recorder);
                notifyDone(job, "已取消本次回答");
                log.info("Run was cancelled at a checkpoint: run={}", job.runId());
                return;
            }
            if (find(error, RunLeaseLostException.class) != null) {
                // 租约已丢失/任务已被标记中断：一个字都不写回，避免覆盖新状态或追加迟到答复。
                log.warn("Run stopped because its lease was lost; no result was written: run={}", job.runId());
                return;
            }
            RunRejectedException rejected = find(error, RunRejectedException.class);
            if (rejected != null) {
                finishRejected(job, lease, recorder, rejected);
                return;
            }
            String code = RunLifecycleService.classify(error);
            lease.failed(recorder, code);
            log.warn("Queued run failed: run={} code={}", job.runId(), code);
            if (find(error, AuthException.class) == null) {
                notifyDone(job, "执行失败，请刷新后重试");
            }
        }
    }

    private void finishRejected(RunQueueJob job, RunLease lease, RunRecorder recorder, RunRejectedException rejected) {
        if (rejected.cancel()) {
            lease.cancelledRun(recorder);
            notifyDone(job, "本次回答已不可用：" + rejected.getMessage());
            return;
        }
        lease.failed(recorder, rejected.code());
        notifyDone(job, "执行失败，请稍后重试");
    }

    /** 账号运行：每个检查点重新校验账号/会话/助手/KB/模型，然后续租。 */
    private Consumer<AgentDTO> accountGuard(RunQueueJob job, RunLease lease) {
        return config -> {
            access.validateRuntime(job.actorUserId(), job.actorAuthVersion(), job.actorSessionHash(),
                    job.sessionId(), config);
            lease.checkpoint();
        };
    }

    /** 游客运行：只允许公开助手 + 固定四工具；每次检查点确认配置仍是公开配置并续租。 */
    private Consumer<AgentDTO> demoGuard(RunLease lease) {
        return config -> {
            demo.validateAgent(config);
            lease.checkpoint();
        };
    }

    /** 失败/取消通知同样重新校验；账号撤销后连收尾事件都不发。 */
    private void notifyDone(RunQueueJob job, String statusText) {
        try {
            if (job.origin() == RunOrigin.ACCOUNT) {
                access.requireExecutionActor(job.actorUserId(), job.actorAuthVersion(), job.actorSessionHash(),
                        job.sessionId(), job.agentId());
            }
            sse.send(job.sessionId(), SseMessage.builder().type(SseMessage.Type.AI_DONE)
                    .payload(SseMessage.Payload.builder().done(true).statusText(statusText).build())
                    .metadata(SseMessage.Metadata.builder().runId(job.runId()).build()).build());
        } catch (RuntimeException ignored) {
            // 已撤销的会话不再收到任何迟到事件。
        }
    }

    private RunEventSink sink() {
        return new RunEventSink() {
            @Override
            public void append(RunEventRecord event) {
                runs.appendEvent(event);
            }

            @Override
            public void touch(String runId) {
                runs.touch(runId);
            }

            @Override
            public void setAnswerMessageId(String runId, String answerMessageId) {
                runs.setAnswerMessageId(runId, answerMessageId);
            }
        };
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }
}
