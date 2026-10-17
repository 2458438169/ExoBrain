package com.kama.jchatmind.retrieval;

/** 索引构建/发布失败。消息可以进入服务端日志与上传错误提示，但不得包含正文、凭据或服务商原始异常。 */
public class IndexingException extends RuntimeException {
    public IndexingException(String message) {
        super(message);
    }

    public IndexingException(String message, Throwable cause) {
        super(message, cause);
    }
}