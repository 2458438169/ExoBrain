package com.kama.jchatmind.models;

/**
 * 供应商调用失败，但带着可记录的**白名单诊断**（见 {@link ProviderFailure}）。
 *
 * <p>消息仍是固定安全文案，不含供应商的响应体或链接；诊断只走 {@link #failure()}，
 * 由调用方写进模型调用事件的 {@code detail}。</p>
 */
public class ProviderUnavailableException extends ModelException {

    private final ProviderFailure failure;

    public ProviderUnavailableException(ProviderFailure failure) {
        super(502, "语言模型服务暂不可用，请联系管理员检查模型配置");
        this.failure = failure;
    }

    public ProviderFailure failure() {
        return failure;
    }
}
