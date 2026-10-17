package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 文档列表项。
 *
 * <p>导入状态为增量字段（契约 §6）：{@code jobId/indexStatus/chunksDone/chunksTotal} 来自该文档最新的
 * 导入任务；迁移前上传的旧文档没有任务，这些字段为 {@code null}，界面按“未知”处理而不是编造状态。
 * {@code chunksTotal} 未知时为 {@code null}（那时只显示阶段，不生成虚假百分比）。</p>
 */
@Data
@Builder
public class DocumentVO {
    private String id;
    private String kbId;
    private String filename;
    private String filetype;
    private Long size;
    private boolean readOnly;
    private String jobId;
    private String indexStatus;
    private String indexErrorCode;
    private String indexErrorMessage;
    private Integer chunksDone;
    private Integer chunksTotal;
}
