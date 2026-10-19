package com.kama.jchatmind.execution;

import com.kama.jchatmind.auth.AuthException;
import com.kama.jchatmind.agent.InvalidAnswerFormatException;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.retrieval.RetrievalAccessDeniedException;
import com.kama.jchatmind.models.ModelCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 运行记录的创建与终态更新。
 *
 * <p>账号运行由 {@code ChatEventListener} 调用、游客运行由 {@code DemoExecutionService} 调用。
 * 主记录创建失败会向上抛：调用方不得把“没有执行记录”的执行显示成成功启动。中途事件写入失败
 * 不会中断执行，只把 {@code traceIncomplete} 置位。</p>
 */
@Service
public class RunLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(RunLifecycleService.class);

    /** 一次已创建的运行：运行标识、记录器与服务端计时起点。 */
    public record RunStart(String runId, RunRecorder recorder, Instant submittedAt, Instant startedAt,
                           long startNanos) {
    }

    private final RunRepository runs;
    private final AgentMapper agents;
    private final ChatMessageMapper messages;
    private final ModelCatalogService models;
    private final RunEventSink sink;

    public RunLifecycleService(RunRepository runs, AgentMapper agents, ChatMessageMapper messages,
                               ModelCatalogService models) {
        this.runs = runs;
        this.agents = agents;
        this.messages = messages;
        this.models = models;
        this.sink = new RunEventSink() {
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

    public RunStart startAccountRun(String sessionId, String triggeringMessageId, String agentId) {
        return start(RunOrigin.ACCOUNT, sessionId, triggeringMessageId, agentId);
    }

    public RunStart startDemoRun(String sessionId, String triggeringMessageId, String agentId) {
        return start(RunOrigin.DEMO, sessionId, triggeringMessageId, agentId);
    }

    private RunStart start(RunOrigin origin, String sessionId, String triggeringMessageId, String agentId) {
        String session = requireUuid(sessionId, "会话标识");
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        Agent agent = loadAgent(agentId);
        Instant submittedAt = submittedAt(triggeringMessageId, startedAt);
        Long queueDurationMs = submittedAt.isAfter(startedAt)
                ? null : Duration.between(submittedAt, startedAt).toMillis();
        String runId = UUID.randomUUID().toString();
        RunSummary summary = new RunSummary(
                RunSummary.SCHEMA_VERSION,
                runId,
                session,
                optionalUuid(triggeringMessageId),
                agent == null ? null : optionalUuid(agent.getId()),
                agent == null ? null : agent.getName(),
                agent == null ? null : configHash(agent),
                agent == null ? null : agent.getModel(),
                agent == null ? null : providerModelName(agent.getModel()),
                RunStatus.RUNNING,
                submittedAt,
                startedAt,
                null,
                null,
                queueDurationMs,
                null, null, null,
                null,
                null,
                false,
                startedAt,
                origin);
        runs.create(summary);
        return new RunStart(runId, new RunRecorder(runId, sink, startNanos), submittedAt, startedAt, startNanos);
    }

    /** 正常结束：终态更新是幂等的，失败只记安全日志，不影响已经交付的答复。 */
    public void succeeded(RunStart start) {
        complete(start, RunStatus.SUCCEEDED, null);
    }

    /** 失败：先记录一个 ERROR 事件（安全错误码），再写终态。 */
    public void failed(RunStart start, String errorCode) {
        if (start != null) {
            start.recorder().note(RunEventType.ERROR, RunEventStatus.FAILED, errorCode, null);
        }
        complete(start, RunStatus.FAILED, errorCode);
    }

    public void complete(RunStart start, RunStatus status, String errorCode) {
        if (start == null) {
            return;
        }
        try {
            int updated = runs.complete(start.runId(), status, Instant.now(), start.recorder().durationMillis(),
                    start.recorder().usage(), errorCode, start.recorder().traceIncomplete());
            if (updated == 0) {
                log.warn("Run terminal state was not applied (already final or missing): run={} status={}",
                        start.runId(), status);
            }
        } catch (RuntimeException failure) {
            log.error("Run terminal state could not be persisted: run={} status={}", start.runId(), status);
        }
    }

    /** 安全错误分类：只保留固定码，不回传堆栈或服务商原文。 */
    public static String classify(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidAnswerFormatException) {
                return "INVALID_ANSWER_FORMAT";
            }
            if (cause instanceof AuthException) {
                return "AUTHORIZATION_REVOKED";
            }
            if (cause instanceof RetrievalAccessDeniedException) {
                return "RETRIEVAL_DENIED";
            }
            String message = cause.getMessage();
            if (message != null && message.contains("上游模型繁忙")) {
                return "MODEL_UNAVAILABLE";
            }
        }
        return "EXECUTION_FAILED";
    }

    private Agent loadAgent(String agentId) {
        String id = optionalUuid(agentId);
        if (id == null) {
            return null;
        }
        try {
            return agents.selectById(id);
        } catch (RuntimeException unavailable) {
            log.warn("Run identity could not load the agent configuration: agent={}", id);
            return null;
        }
    }

    private Instant submittedAt(String triggeringMessageId, Instant startedAt) {
        String id = optionalUuid(triggeringMessageId);
        if (id == null) {
            return startedAt;
        }
        try {
            ChatMessage message = messages.selectById(id);
            LocalDateTime createdAt = message == null ? null : message.getCreatedAt();
            return createdAt == null ? startedAt : createdAt.atZone(ZoneId.systemDefault()).toInstant();
        } catch (RuntimeException unavailable) {
            return startedAt;
        }
    }

    private String providerModelName(String modelCatalogId) {
        if (modelCatalogId == null) {
            return null;
        }
        try {
            return models.requireModel(modelCatalogId).model();
        } catch (RuntimeException unknownModel) {
            return null;
        }
    }

    /** 助手配置标识：只存 SHA-256，不存配置快照，避免提示词等进入运行记录。 */
    static String configHash(Agent agent) {
        String canonical = String.join("\u0000",
                nullToEmpty(agent.getName()),
                nullToEmpty(agent.getDescription()),
                nullToEmpty(agent.getSystemPrompt()),
                nullToEmpty(agent.getModel()),
                nullToEmpty(agent.getAllowedTools()),
                nullToEmpty(agent.getAllowedKbs()),
                nullToEmpty(agent.getChatOptions()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String requireUuid(String value, String label) {
        String id = optionalUuid(value);
        if (id == null) {
            throw new IllegalArgumentException(label + " is not a UUID");
        }
        return id;
    }

    private static String optionalUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value)
                    ? UUID.fromString(value).toString() : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
