package com.kama.jchatmind.runs;

/**
 * 排队工作开始前的重新校验失败：身份/权限/配置已经变化。
 *
 * @param code   安全的固定错误码（写入运行摘要，不写服务商原始异常）
 * @param cancel 是否为“资源已不可用/权限撤销”（CANCELLED）而不是失败（FAILED）
 */
public class RunRejectedException extends RuntimeException {

    private final String code;
    private final boolean cancel;

    public RunRejectedException(String code, String message, boolean cancel) {
        super(message);
        this.code = code;
        this.cancel = cancel;
    }

    public String code() {
        return code;
    }

    public boolean cancel() {
        return cancel;
    }
}
