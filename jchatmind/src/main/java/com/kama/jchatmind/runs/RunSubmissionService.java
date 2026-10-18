package com.kama.jchatmind.runs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.execution.RunOrigin;
import com.kama.jchatmind.execution.RunRepository;
import com.kama.jchatmind.execution.RunStatus;
import com.kama.jchatmind.execution.RunSummary;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.models.ModelCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * “已受理”的提交边界：用户消息、固定历史快照、运行摘要（QUEUED）与排队任务在<b>同一个事务</b>里写入。
 *
 * <p>可靠来源是数据库队列：事务提交后发出的 {@link ChatEvent} 只是唤醒 worker 的提示，
 * 即使这次 JVM 事件丢失（进程崩溃/重启），{@link RunWorker} 的轮询仍会从 {@code QUEUED} 继续。</p>
 *
 * <p>幂等：客户端为每个“新的提交意图”生成一个 {@code idempotencyKey}，重试时复用；服务端以
 * {@code (actor_scope, session_id, idempotency_key)} 唯一约束为准。同一个键 + 同一个请求摘要返回原来的
 * message/run（不再向模型发送第二遍），同一个键 + 不同摘要明确拒绝。绝不按问题文本去重。</p>
 *
 * <p>同会话冲突由数据库的部分唯一索引（每会话最多一个 QUEUED/RUNNING）保证；这里先做一次可读性更好的
 * 预检查，唯一索引仍是最终裁判（并发时后到者回滚并返回 409）。</p>
 */
@Service
public class RunSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(RunSubmissionService.class);

    /** 提交结果：与 HTTP 响应一一对应。 */
    public record Submission(String chatMessageId, String runId, String runStatus, boolean replay) {
        public CreateChatMessageResponse toResponse() {
            return CreateChatMessageResponse.builder()
                    .chatMessageId(chatMessageId)
                    .runId(runId)
                    .runStatus(runStatus)
                    .idempotentReplay(replay)
                    .build();
        }
    }

    private final ChatMessageMapper messages;
    private final ChatMessageConverter converter;
    private final ObjectMapper json;
    private final RunRepository runs;
    private final RunQueueRepository jobs;
    private final RunQueueSettings settings;
    private final AgentMapper agents;
    private final ModelCatalogService models;
    private final ApplicationEventPublisher publisher;

    public RunSubmissionService(ChatMessageMapper messages, ChatMessageConverter converter, ObjectMapper json,
                                RunRepository runs, RunQueueRepository jobs, RunQueueSettings settings,
                                AgentMapper agents, ModelCatalogService models,
                                ApplicationEventPublisher publisher) {
        this.messages = messages;
        this.converter = converter;
        this.json = json;
        this.runs = runs;
        this.jobs = jobs;
        this.settings = settings;
        this.agents = agents;
        this.models = models;
        this.publisher = publisher;
    }

    @Transactional
    public Submission submitAccount(String actorId, int authVersion, String tokenHash, String agentId,
                                    String sessionId, String content, String idempotencyKey) {
        return submit(RunOrigin.ACCOUNT, "ACCOUNT:" + actorId, actorId, authVersion, tokenHash, null,
                agentId, sessionId, content, idempotencyKey);
    }

    @Transactional
    public Submission submitDemo(String ownerHash, String agentId, String sessionId, String content,
                                 String idempotencyKey) {
        return submit(RunOrigin.DEMO, "DEMO:" + ownerHash, null, null, null, ownerHash,
                agentId, sessionId, content, idempotencyKey);
    }

    /**
     * 事务回滚后再次读取（并发重复提交或唯一约束冲突）。返回原来的 message/run，或抛出明确的冲突。
     */
    public Submission replayAfterConflict(RunOrigin origin, String actorScope, String sessionId, String key,
                                          String digest) {
        Optional<RunQueueJob> existing = jobs.findByIdempotencyKey(actorScope, normalizeKey(key), sessionId);
        if (existing.isPresent()) {
            return replay(existing.get(), digest);
        }
        throw new RunSubmissionException(409, "SUBMISSION_CONFLICT", "提交请求发生冲突，请重试");
    }

    public static String requestDigest(RunOrigin origin, String sessionId, String agentId, String content) {
        return sha256(String.join("\u0000", origin.name(), String.valueOf(sessionId), String.valueOf(agentId),
                "USER", content == null ? "" : content));
    }

    public static String actorScope(RunOrigin origin, String actor) {
        return (origin == RunOrigin.ACCOUNT ? "ACCOUNT:" : "DEMO:") + actor;
    }

    // ------------------------------------------------------------------ internals

    private Submission submit(RunOrigin origin, String actorScope, String actorUserId, Integer authVersion,
                              String tokenHash, String demoOwner, String agentId, String sessionId, String content,
                              String idempotencyKey) {
        String digest = requestDigest(origin, sessionId, agentId, content);
        String key = normalizeKey(idempotencyKey);
        Optional<RunQueueJob> existing = jobs.findByIdempotencyKey(actorScope, sessionId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), digest);
        }
        // 队列不能成为无限缓存：每个身份的活动数、全局排队数都有上限。
        if (jobs.countActiveForActor(actorScope) >= settings.maxActivePerActor()) {
            throw new RunSubmissionException(429, "TOO_MANY_ACTIVE_RUNS", "排队中的问答过多，请等待完成后再提问");
        }
        if (jobs.countQueued() >= settings.maxQueued()) {
            throw new RunSubmissionException(429, "QUEUE_FULL", "系统繁忙，请稍后再试");
        }
        Optional<RunQueueJob> active = jobs.findActiveBySession(sessionId);
        if (active.isPresent()) {
            if (active.get().idempotencyKey().equals(key) && active.get().actorScope().equals(actorScope)) {
                return replay(active.get(), digest);
            }
            throw new RunSubmissionException(409, "SESSION_BUSY", "上一条消息仍在处理中，请等待完成");
        }

        Agent agent = agents.selectById(agentId);
        if (agent == null) {
            throw new RunSubmissionException(404, "AGENT_NOT_FOUND", "助手不存在");
        }
        // 固定提交快照：历史在插入新消息之前读取，随后把新消息追加到末尾（与 05 的触发消息语义一致）。
        List<ChatMessageDTO> snapshot = recent(sessionId);
        ChatMessage message = insertUserMessage(sessionId, content);
        ChatMessageDTO trigger;
        try {
            trigger = converter.toDTO(message);
        } catch (JsonProcessingException invalid) {
            throw new RunSubmissionException(400, "SNAPSHOT_INVALID", "无法创建本次会话的历史快照");
        }
        snapshot.add(trigger);
        String historySnapshot;
        try {
            historySnapshot = json.writeValueAsString(snapshot);
        } catch (JsonProcessingException invalid) {
            throw new RunSubmissionException(400, "SNAPSHOT_INVALID", "无法创建本次会话的历史快照");
        }

        Instant submittedAt = message.getCreatedAt() == null ? Instant.now()
                : message.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
        String runId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        // 运行摘要与任务同一事务：返回成功就代表两者都已经落库。
        runs.create(new RunSummary(RunSummary.SCHEMA_VERSION, runId, sessionId, message.getId(), agentId,
                agent.getName(), RunConfigHash.of(agent), agent.getModel(), providerModelName(agent.getModel()),
                RunStatus.QUEUED, submittedAt, null, null, null, null, null, null, null, null, null, false,
                submittedAt, origin));
        jobs.insert(new RunQueueJob(jobId, runId, origin, sessionId, message.getId(), agentId, actorScope,
                actorUserId, authVersion, tokenHash, demoOwner, key, digest, content, historySnapshot,
                RunQueueStatus.QUEUED, 0, 1, false, null, null, 0L, null, submittedAt, null, null));

        wakeAfterCommit(new ChatEvent(runId, sessionId, origin.name()));
        return new Submission(message.getId(), runId, RunQueueStatus.QUEUED.name(), false);
    }

    private Submission replay(RunQueueJob job, String digest) {
        if (!job.requestDigest().equals(digest)) {
            throw new RunSubmissionException(409, "IDEMPOTENCY_KEY_REUSED",
                    "同一幂等键被用于不同的请求内容，已拒绝");
        }
        return new Submission(job.triggeringMessageId(), job.runId(), job.status().name(), true);
    }

    private List<ChatMessageDTO> recent(String sessionId) {
        List<ChatMessage> recent = messages.selectBySessionIdRecently(sessionId, 100);
        List<ChatMessageDTO> snapshot = new ArrayList<>();
        for (ChatMessage message : recent) {
            try {
                snapshot.add(converter.toDTO(message));
            } catch (JsonProcessingException invalid) {
                throw new RunSubmissionException(400, "SNAPSHOT_INVALID", "无法读取本次会话的历史快照");
            }
        }
        return snapshot;
    }

    private ChatMessage insertUserMessage(String sessionId, String content) {
        try {
            LocalDateTime now = LocalDateTime.now();
            ChatMessage entity = converter.toEntity(ChatMessageDTO.builder().sessionId(sessionId)
                    .role(ChatMessageDTO.RoleType.USER).content(content).build());
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            if (messages.insert(entity) <= 0) {
                throw new RunSubmissionException(500, "MESSAGE_NOT_SAVED", "创建聊天消息失败");
            }
            return entity;
        } catch (JsonProcessingException serialization) {
            throw new RunSubmissionException(500, "MESSAGE_NOT_SAVED", "创建聊天消息失败");
        }
    }

    private String normalizeKey(String key) {
        String normalized = key == null ? "" : key.strip();
        if (normalized.isEmpty()) {
            return "auto-" + UUID.randomUUID();
        }
        if (normalized.length() > settings.maxIdempotencyKeyLength()) {
            throw new RunSubmissionException(400, "IDEMPOTENCY_KEY_INVALID", "幂等键过长");
        }
        return normalized;
    }

    private String providerModelName(String modelCatalogId) {
        if (modelCatalogId == null) {
            return null;
        }
        try {
            return models.requireModel(modelCatalogId).model();
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    /** 只在事务提交后唤醒 worker；事件丢失也不影响队列恢复。 */
    private void wakeAfterCommit(ChatEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publishEvent(event);
                }
            });
            return;
        }
        log.warn("Chat submission was not inside a transaction; waking the worker directly: session={}",
                event.getSessionId());
        publisher.publishEvent(event);
    }

    private static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
