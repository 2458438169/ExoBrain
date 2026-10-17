package com.kama.jchatmind.retrieval;

import java.util.List;

/**
 * 来源位置（契约 §2 / §3）。
 *
 * <p>{@code sourceHash/startLine/endLine} 允许为 null：legacy chunk 没有可靠位置时界面只能打开整份文档，
 * 绝不按片段在原文中的第一次字符串匹配假定位置。<b>不会伪造位置。</b></p>
 */
public record SourceLocation(
        String documentId,
        String documentName,
        List<String> headingPath,
        String sourceHash,
        Integer startLine,
        Integer endLine) {

    public SourceLocation {
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
    }

    /** legacy 行没有来源位置，只能定位到整份文档。 */
    public static SourceLocation withoutPosition(String documentId, String documentName, List<String> headingPath) {
        return new SourceLocation(documentId, documentName, headingPath, null, null, null);
    }
}