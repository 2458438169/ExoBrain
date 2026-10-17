package com.kama.jchatmind.ingestion;

/**
 * worker 内部使用的失败信号：携带安全的错误分类，由 {@link IngestionJobRunner} 统一转成
 * {@code RETRY_WAIT / FAILED / CANCELLED}。消息只用于服务端日志，不会直接返回给用户。
 */
public class IngestionFailure extends RuntimeException {

    private final IngestionErrorCode code;

    public IngestionFailure(IngestionErrorCode code) {
        this(code, code.name());
    }

    public IngestionFailure(IngestionErrorCode code, String internalDetail) {
        super(internalDetail == null ? code.name() : internalDetail);
        this.code = code;
    }

    public IngestionErrorCode code() {
        return code;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        // 控制流信号，不需要栈；真正的异常在调用处已经记录。
        return this;
    }
}
