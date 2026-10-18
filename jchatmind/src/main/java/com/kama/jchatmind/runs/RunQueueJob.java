package com.kama.jchatmind.runs;

import com.kama.jchatmind.execution.RunOrigin;

import java.time.Instant;

/**
 * 一行排队任务（表 {@code exobrain_chat_run_job}）。
 *
 * <p>只包含服务端内部字段：{@code actorSessionHash} 与 {@code demoOwner} 都是哈希值，不是原始 Cookie 或令牌；
 * 它们只用于后台线程重新授权，不得出现在 API 响应、SSE 或日志里。</p>
 *
 * @param id                 任务 ID
 * @param runId              05 的运行摘要 ID（提交事务里创建，状态 QUEUED）
 * @param origin             ACCOUNT / DEMO
 * @param sessionId          会话 ID
 * @param triggeringMessageId 触发本次运行的用户消息
 * @param agentId            助手 ID
 * @param actorScope         幂等作用域：{@code ACCOUNT:<userId>} / {@code DEMO:<ownerHash>}
 * @param actorUserId        账号 ID（游客为空）
 * @param actorAuthVersion   提交时的会话授权版本（账号）
 * @param actorSessionHash   提交时的会话令牌哈希（账号，仅服务端）
 * @param demoOwner          访客 Cookie 的 SHA-256（游客，仅服务端）
 * @param idempotencyKey     客户端每次提交意图生成的键，重试复用
 * @param requestDigest      本次请求摘要；同键不同摘要必须拒绝
 * @param userInput          固定的触发问题
 * @param historySnapshot    固定的历史快照（JSON）
 * @param status             任务状态
 * @param attempts           已领取次数（RUNNING 不会被自动重新领取，仅用于诊断）
 * @param maxAttempts        领取次数上限（默认 1：中断的运行不自动重放）
 * @param cancelRequested    运行中取消请求（协作式停止）
 * @param leaseOwner         租约持有者（实例/进程标识）
 * @param leaseExpiresAt     租约到期时间
 * @param fencingToken       每次领取 +1；所有写回都校验它
 * @param errorCode          安全错误码
 * @param createdAt          提交时间
 * @param startedAt          开始执行时间
 * @param finishedAt         终态时间
 */
public record RunQueueJob(
        String id,
        String runId,
        RunOrigin origin,
        String sessionId,
        String triggeringMessageId,
        String agentId,
        String actorScope,
        String actorUserId,
        Integer actorAuthVersion,
        String actorSessionHash,
        String demoOwner,
        String idempotencyKey,
        String requestDigest,
        String userInput,
        String historySnapshot,
        RunQueueStatus status,
        int attempts,
        int maxAttempts,
        boolean cancelRequested,
        String leaseOwner,
        Instant leaseExpiresAt,
        long fencingToken,
        String errorCode,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public RunQueueJob {
        if (id == null || runId == null || origin == null || sessionId == null || actorScope == null
                || idempotencyKey == null || requestDigest == null || userInput == null
                || historySnapshot == null || status == null || createdAt == null) {
            throw new IllegalArgumentException("排队任务缺少必填字段");
        }
    }
}
