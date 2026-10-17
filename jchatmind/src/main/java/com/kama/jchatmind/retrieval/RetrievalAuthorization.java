package com.kama.jchatmind.retrieval;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * 调用方提供给检索服务的授权判断（契约 §2：“授权在调用模型/向量查询之前执行”）。
 *
 * <p>账号工具的实际权限边界在运行时的 {@code ScopedToolCallback} + {@code runtimeGuard}：
 * 模型给出的 kbsId 先被限定在该助手允许的知识库集合内，工具才会被调用。工具因此把“只检索本次调用
 * 参数里的那个获准 KB”作为显式判断传给服务；演示工具传入公开白名单判断。
 * 服务本身不会绕过这个判断去查任何未授权知识库。</p>
 */
public final class RetrievalAuthorization {

    private RetrievalAuthorization() {
    }

    /** 只允许与工具参数一致、且是合法 UUID 的知识库 id（其余由运行时围栏先行拒绝）。 */
    public static Predicate<String> argumentBound(String kbsId) {
        return candidate -> candidate != null && kbsId != null && candidate.equalsIgnoreCase(kbsId)
                && isUuid(candidate);
    }

    /** 公开演示白名单判断。 */
    public static Predicate<String> publishedOnly(Predicate<String> isPublicKb) {
        return isPublicKb;
    }

    static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return false;
        }
    }
}
