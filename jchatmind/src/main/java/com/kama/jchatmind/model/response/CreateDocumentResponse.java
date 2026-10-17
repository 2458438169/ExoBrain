package com.kama.jchatmind.model.response;

import lombok.Builder;
import lombok.Data;

/**
 * 上传/创建文档的响应。
 *
 * <p>异步导入后新增可选字段 {@code jobId/indexStatus}；既有 {@code documentId} 保留不变，
 * 旧客户端忽略新字段也能继续工作（只是不知道索引进度）。{@code indexStatus} 只会是
 * {@code QUEUED}（已受理，等待 worker）或兼容构造器的 {@code SUCCEEDED}（无 worker 的同步路径）。</p>
 */
@Data
@Builder
public class CreateDocumentResponse {
    private String documentId;
    private String jobId;
    private String indexStatus;
}
