package com.kama.jchatmind.ingestion;

/**
 * 导入任务状态（共享契约 §6）：{@code QUEUED → RUNNING → SUCCEEDED}；临时失败进入
 * {@code RETRY_WAIT} 后重新排队，超过尝试上限为 {@code FAILED}；资源删除/权限撤销为
 * {@code CANCELLED}。
 *
 * <p>任务成功只表示<b>可用索引已原子发布</b>；文件接收成功（上传返回 200）不等于可检索。</p>
 */
public enum IngestionStatus {
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /** 还会继续被 worker 领取的状态（数据库里有唯一索引保证同一文档最多一个）。 */
    public boolean isActive() {
        return !isTerminal();
    }

    public static IngestionStatus fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("任务状态缺失");
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("未知的任务状态");
        }
    }
}
