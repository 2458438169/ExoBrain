package com.kama.jchatmind.ingestion;

import java.time.Instant;

/**
 * 一条持久化导入任务的快照（表 {@code exobrain_ingestion_job}）。
 *
 * <p>只包含服务器生成的资源标识：{@code kbId}/{@code documentId} 是数据库 UUID，
 * {@code filePath} 是 {@code kbId/documentId/filename} 形式的受控相对路径，{@code actorUserId}
 * 来自认证上下文而不是请求体。任务不携带正文、密钥或客户端传来的任意路径。</p>
 */
public record IngestionJob(String id, String kbId, String documentId, String filePath, String sourceHash,
                           String actorUserId, IngestionStatus status, int attempts, int maxAttempts,
                           Instant nextAttemptAt, String leaseOwner, Instant leaseExpiresAt, long fencingToken,
                           String indexRevisionId, int chunksDone, Integer chunksTotal,
                           String errorCode, String errorMessage, Instant createdAt, Instant startedAt,
                           Instant finishedAt) {

    /** 领取时使用的租约持有者标识（进程身份 + 随机串），不写入公开响应。 */
    public boolean isRunning() {
        return status == IngestionStatus.RUNNING;
    }

    public boolean leaseLive(Instant now) {
        return leaseExpiresAt == null || leaseExpiresAt.isAfter(now);
    }
}
