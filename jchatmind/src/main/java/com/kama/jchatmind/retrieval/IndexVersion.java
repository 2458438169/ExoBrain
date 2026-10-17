package com.kama.jchatmind.retrieval;

import java.util.Locale;

/**
 * 索引语义版本（契约 §3 的 {@code indexVersion}）：表示切块方式、Embedding 输入与距离配置的语义。
 *
 * <ul>
 *   <li>{@link #LEGACY_TITLE_V1}：基线行为——每个章节标题一个向量、chunk 只保存正文、没有来源位置。</li>
 *   <li>{@link #BODY_V1}：正文切块——标题路径 + 正文一起进入 Embedding，chunk 记录来源行号与源 hash。</li>
 * </ul>
 */
public enum IndexVersion {
    LEGACY_TITLE_V1("legacy-title-v1"),
    BODY_V1("body-v1");

    private final String wire;

    IndexVersion(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static IndexVersion fromWire(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (IndexVersion version : values()) {
            if (version.wire.equals(normalized)) {
                return version;
            }
        }
        throw new IllegalArgumentException("unknown index version: " + value);
    }
}
