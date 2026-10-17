package com.kama.jchatmind.retrieval;

/**
 * 安全固定错误码：可以进入工具结果与公开日志，不包含服务商原始异常、地址、凭据或堆栈。
 *
 * <p>未授权不属于这里——它是访问失败（{@link RetrievalAccessDeniedException}），不会伪装成一次成功检索。</p>
 */
public final class RetrievalErrorCode {
    /** 知识库尚未建立任何可用索引（没有活动版本、也没有 legacy 行）。 */
    public static final String NO_INDEXED_CONTENT = "NO_INDEXED_CONTENT";
    /** 有索引，但本次查询没有命中到校准阈值以内的资料。 */
    public static final String NO_MATCH = "NO_MATCH";
    /** 知识库没有绑定可用的向量模型，或绑定的模型未启用。 */
    public static final String MODEL_BINDING_UNAVAILABLE = "MODEL_BINDING_UNAVAILABLE";
    /** 向量服务调用失败或返回了无效向量。 */
    public static final String EMBEDDING_UNAVAILABLE = "EMBEDDING_UNAVAILABLE";
    /** 索引元数据（活动版本、revision、行数据）读取失败。 */
    public static final String INDEX_UNAVAILABLE = "INDEX_UNAVAILABLE";
    /** 索引行返回了非有限或负的距离，属于索引损坏，不能伪造一个距离值。 */
    public static final String INDEX_INVALID = "INDEX_INVALID";
    /** 工具参数无效（例如 kbsId 不是 UUID）。 */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    /** 兜底的检索不可用（未分类失败），同样不携带服务商原始异常。 */
    public static final String RETRIEVAL_UNAVAILABLE = "RETRIEVAL_UNAVAILABLE";
    /** 公开演示工具收到不在发布白名单里的知识库。 */
    public static final String KB_NOT_ALLOWED = "KB_NOT_ALLOWED";

    private RetrievalErrorCode() {
    }
}