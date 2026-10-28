package com.kama.jchatmind.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 供应商失败诊断的白名单：只保留状态码、Retry-After、稳定错误码与请求标识。
 *
 * <p>这些断言的意义是「不泄漏」而不是「信息越多越好」：响应体里的密钥、URL、
 * 甚至整段错误消息都不能进 detail；拿不到的字段必须记 {@code unknown}。</p>
 */
class ProviderFailureTest {

    @Test
    @DisplayName("白名单字段按原样保留，detail 有固定键名与顺序")
    void allowListedFieldsAreKept() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "12");
        headers.add("x-request-id", "req-9f8e7d");

        ProviderFailure failure = ProviderFailure.of(429, headers,
                "{\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"slow down\"}}");

        assertEquals(429, failure.status());
        assertEquals("12", failure.retryAfter());
        assertEquals("rate_limit_exceeded", failure.providerCode());
        assertEquals("req-9f8e7d", failure.requestId());
        assertEquals("http=429;retry-after=12;code=rate_limit_exceeded;req=req-9f8e7d", failure.toDetail());
        assertTrue(failure.throttled());
    }

    @Test
    @DisplayName("响应体里的密钥与链接不会进入 detail")
    void secretsAndUrlsNeverLeak() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "Wed, 21 Oct 2015 07:28:00 GMT");
        headers.add("x-request-id", "https://relay.example/v1?key=sk-abcdefghijk");

        String body = "{\"error\":{\"code\":\"insufficient_quota\","
                + "\"message\":\"key sk-abcdefghijk is invalid, see https://relay.example/v1\"}}";
        ProviderFailure failure = ProviderFailure.of(400, headers, body);

        // 不能解析成秒数的 Retry-After、形状不安全的请求标识都记 unknown。
        assertEquals("unknown", failure.retryAfter());
        assertEquals("unknown", failure.requestId());
        assertEquals("insufficient_quota", failure.providerCode());
        String detail = failure.toDetail();
        assertFalse(detail.contains("sk-"), detail);
        assertFalse(detail.contains("http://"), detail);
        assertFalse(detail.contains("https://"), detail);
        assertFalse(detail.contains("relay.example"), detail);
        assertTrue(detail.length() <= 200);
    }

    @Test
    @DisplayName("拿不到的字段记 unknown，不猜原因；非 JSON 响应体只留下状态码")
    void missingFieldsBecomeUnknown() {
        ProviderFailure failure = ProviderFailure.of(500, new HttpHeaders(), "<html>503 upstream busy</html>");

        assertEquals("unknown", failure.retryAfter());
        assertEquals("unknown", failure.providerCode());
        assertEquals("unknown", failure.requestId());
        assertEquals("http=500;retry-after=unknown;code=unknown;req=unknown", failure.toDetail());
        assertFalse(failure.throttled(), "500 不是限流，不该按限流重试");
    }

    @Test
    @DisplayName("错误码形状不安全时丢弃，避免把整段消息塞进诊断字段")
    void unsafeCodeIsDropped() {
        ProviderFailure failure = ProviderFailure.of(429, new HttpHeaders(),
                "{\"error\":{\"code\":\"too many requests for key sk-abcdefghijk\"}}");

        assertEquals("unknown", failure.providerCode());
        assertTrue(failure.throttled(), "429 本身足以判定为限流");
    }

    @Test
    @DisplayName("异常链里能找到带诊断的供应商失败；没有时返回 null")
    void diagnosticsAreFoundThroughCauseChain() {
        ProviderFailure failure = ProviderFailure.of(429, new HttpHeaders(), "{}");
        RuntimeException wrapped = new IllegalStateException("outer",
                new ProviderUnavailableException(failure));

        assertEquals(failure, ProviderFailure.find(wrapped));
        assertNull(ProviderFailure.find(new IllegalStateException("timeout")));
    }
}
