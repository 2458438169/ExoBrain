package com.kama.jchatmind.models;

import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.List;

/**
 * 供应商失败的**白名单诊断**：只保留能用于分诊的少数固定字段。
 *
 * <p>不上报响应体、响应头原文、URL 或密钥。字段缺失一律显式记 {@code unknown}，
 * 不用猜测值填充；因此「看到 429 但不知道是 RPM、TPM 还是余额」会如实体现为
 * {@code code=unknown}，而不是编一个原因。</p>
 *
 * <p>允许的字段：</p>
 * <ul>
 *   <li>{@code http}：HTTP 状态码（供应商返回的原始状态）；</li>
 *   <li>{@code retry-after}：{@code Retry-After} 头（秒）；无法解析为整数时记 {@code unknown}；</li>
 *   <li>{@code code}：供应商错误体里的稳定错误码（{@code error.code} / {@code error.type} /
 *       {@code code}），只接受 {@code [A-Za-z0-9_.:-]{1,64}}，其它形状丢弃；</li>
 *   <li>{@code req}：请求标识（{@code x-request-id} / {@code request-id}），只保留前 32 个安全字符。</li>
 * </ul>
 *
 * <p>{@link #toDetail()} 生成的字符串写入模型调用事件的 {@code detail}（上限 200 字符），
 * 供失败分诊使用；它不含任何凭据或可点开的链接。</p>
 */
public record ProviderFailure(int status, String retryAfter, String providerCode, String requestId) {

    private static final String UNKNOWN = "unknown";

    /** 供应商错误码只接受这种形状，避免把整段消息塞进诊断字段。 */
    private static final java.util.regex.Pattern SAFE_CODE =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    /** 请求标识同理：只允许安全字符并限长。 */
    private static final java.util.regex.Pattern SAFE_REQUEST_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.:-]{1,32}");

    public static ProviderFailure of(int status, HttpHeaders headers, String body) {
        return new ProviderFailure(status, header(headers, HttpHeaders.RETRY_AFTER),
                providerCode(body), requestId(headers));
    }

    /** 只从白名单响应头取，不做猜测。 */
    private static String header(HttpHeaders headers, String name) {
        if (headers == null) {
            return UNKNOWN;
        }
        String value = headers.getFirst(name);
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String trimmed = value.strip();
        return trimmed.matches("\\d{1,6}") ? trimmed : UNKNOWN;
    }

    private static String requestId(HttpHeaders headers) {
        if (headers == null) {
            return UNKNOWN;
        }
        for (String name : List.of("x-request-id", "request-id", "x-amzn-requestid", "cf-ray")) {
            String value = headers.getFirst(name);
            if (value == null || value.isBlank()) {
                continue;
            }
            String candidate = value.strip();
            if (SAFE_REQUEST_ID.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return UNKNOWN;
    }

    /**
     * 从响应体里只挑稳定的错误码字段。响应体可能是任意文本（含 HTML 或回显的请求），
     * 所以只做最小的结构识别，取不到就记 {@code unknown}。
     */
    private static String providerCode(String body) {
        if (body == null || body.isBlank()) {
            return UNKNOWN;
        }
        String trimmed = body.strip();
        if (!trimmed.startsWith("{")) {
            return UNKNOWN;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
            List<String> candidates = new ArrayList<>();
            for (String path : List.of("/error/code", "/error/type", "/code", "/error/status")) {
                com.fasterxml.jackson.databind.JsonNode node = root.at(path);
                if (node != null && node.isTextual()) {
                    candidates.add(node.asText());
                }
            }
            for (String candidate : candidates) {
                String value = candidate.strip();
                if (SAFE_CODE.matcher(value).matches()) {
                    return value;
                }
            }
        } catch (RuntimeException | java.io.IOException invalid) {
            return UNKNOWN;
        }
        return UNKNOWN;
    }

    /** 写成模型调用事件的 detail：固定顺序、固定键名、总长受控。 */
    public String toDetail() {
        String detail = "http=" + status + ";retry-after=" + retryAfter + ";code=" + providerCode
                + ";req=" + requestId;
        return detail.length() > 200 ? detail.substring(0, 200) : detail;
    }

    /** 在异常链里找带诊断的供应商失败；找不到返回 null（调用方据此记 unknown）。 */
    public static ProviderFailure find(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ProviderUnavailableException carrier) {
                return carrier.failure();
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    /** 状态码/错误体的组合是否属于「限流类」，用于决定是否值得有界重试。 */
    public boolean throttled() {
        return status == 429 || status == 503 && "rate_limit_exceeded".equals(providerCode);
    }
}
