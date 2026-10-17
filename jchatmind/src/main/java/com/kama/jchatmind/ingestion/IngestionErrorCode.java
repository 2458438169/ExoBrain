package com.kama.jchatmind.ingestion;

/**
 * 安全的失败分类。{@code permanent} 的失败不会自动重试（鉴权、格式、维度、资源状态变化）；
 * {@code retryable} 的失败进入 {@code RETRY_WAIT}，按有上限的退避重试，用完尝试次数后 {@code FAILED}。
 *
 * <p>{@code cancelling} 表示资源已经不存在或权限被撤销：任务直接 {@code CANCELLED}，不再重试，
 * 也不允许把结果发布到已经删除的 KB/文档上。</p>
 *
 * <p>对外文案只使用这里的固定字符串，不返回服务商原始异常、URL、密钥或正文。</p>
 */
public enum IngestionErrorCode {
    EMBEDDING_UNAVAILABLE("向量服务暂不可用，稍后会重试", true, false),
    INDEX_CONFLICT("索引在构建期间发生变化，稍后会重试", true, false),
    FILE_UNREADABLE("暂时无法读取已保存的文件，稍后会重试", true, false),
    PUBLISH_FAILED("索引发布失败，稍后会重试", true, false),
    LEASE_LOST("任务租约已过期，由其他 worker 接手", false, false),

    // 永久失败：已确定的错误（配置、格式、维度、模型停用），不做无意义的重试 → FAILED。
    MODEL_UNAVAILABLE("知识库绑定的向量模型不可用，请联系管理员", false, false),
    MODEL_DISABLED("知识库绑定的向量模型已停用，请联系管理员", false, false),
    EMBEDDING_REJECTED("向量服务拒绝了请求，请检查模型配置", false, false),
    NOT_INDEXABLE("文档没有可索引的正文", false, false),
    TOO_MANY_CHUNKS("文档切出的片段过多，已停止处理", false, false),
    FILE_CHANGED("文件在导入期间被修改，已停止处理", false, false),
    FILE_TOO_LARGE("文件超过允许的大小", false, false),
    UNSUPPORTED_TYPE("暂不支持这种文件类型", false, false),
    ATTEMPTS_EXHAUSTED("多次重试后仍然失败", false, false),
    PUBLISH_REJECTED("索引发布被取消：文档或索引状态已变化", false, false),
    INTERNAL_ERROR("处理失败", false, false),

    // 资源/权限已经变化：直接取消，不重试也不发布 → CANCELLED。
    DOCUMENT_DELETED("文档已删除", false, true),
    KNOWLEDGE_BASE_DELETED("知识库已删除", false, true),
    ACCOUNT_DISABLED("账号已停用或权限已撤销", false, true),
    NOT_AUTHORIZED("无权继续处理该知识库", false, true);

    private final String safeMessage;
    private final boolean retryable;
    private final boolean cancelling;

    IngestionErrorCode(String safeMessage, boolean retryable, boolean cancelling) {
        this.safeMessage = safeMessage;
        this.retryable = retryable;
        this.cancelling = cancelling;
    }

    public String safeMessage() {
        return safeMessage;
    }

    public boolean retryable() {
        return retryable;
    }

    /** 资源或权限已经变化：直接取消，不重试。 */
    public boolean cancelling() {
        return cancelling;
    }
}
