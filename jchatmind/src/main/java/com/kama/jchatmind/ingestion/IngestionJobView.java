package com.kama.jchatmind.ingestion;

import java.time.Instant;

/**
 * 对外返回的导入状态（契约 §6 定稿的字段）。只包含安全信息：状态、真实 chunk 计数、
 * 固定错误码与固定文案；不含文件路径、actor、租约、服务商异常或正文。
 *
 * <p>{@code chunksTotal} 未知时为 {@code null}，界面此时只显示阶段，不生成虚假百分比。</p>
 */
public record IngestionJobView(String jobId, String documentId, String kbId, String status,
                               int attempts, int maxAttempts, int chunksDone, Integer chunksTotal,
                               String errorCode, String errorMessage, boolean retryable,
                               Instant nextAttemptAt, Instant finishedAt, String indexRevisionId) {

    public static IngestionJobView from(IngestionJob job) {
        return new IngestionJobView(job.id(), job.documentId(), job.kbId(), job.status().name(),
                job.attempts(), job.maxAttempts(), job.chunksDone(), job.chunksTotal(),
                job.errorCode(), job.errorMessage(), retryable(job), job.nextAttemptAt(),
                job.finishedAt(), job.indexRevisionId());
    }

    /** 只有失败/取消且资源仍在时才建议重试；重试接口本身还会再校验 KB 写权限。 */
    private static boolean retryable(IngestionJob job) {
        if (job.status() == IngestionStatus.FAILED) {
            return true;
        }
        return job.status() == IngestionStatus.CANCELLED
                && job.errorCode() != null
                && !IngestionErrorCode.DOCUMENT_DELETED.name().equals(job.errorCode())
                && !IngestionErrorCode.KNOWLEDGE_BASE_DELETED.name().equals(job.errorCode())
                && !IngestionErrorCode.ACCOUNT_DISABLED.name().equals(job.errorCode());
    }
}
