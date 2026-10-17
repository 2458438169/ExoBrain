package com.kama.jchatmind.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 文档清单 + 源 hash 的确定性摘要，用于“候选与活动版本是否一致”的比对。 */
public final class SourceSets {

    private SourceSets() {
    }

    public static String hash(List<SourceRef> refs) {
        List<String> lines = new ArrayList<>();
        for (SourceRef ref : refs) {
            lines.add(ref.documentId() + ":" + (ref.sourceHash() == null ? "-" : ref.sourceHash()));
        }
        lines.sort(Comparator.naturalOrder());
        return SourceText.sha256Hex(String.join("\n", lines));
    }

    /** 不比较顺序、只比较集合（候选与活动版本必须覆盖完全相同的文档与源 hash）。 */
    public static boolean sameSet(List<SourceRef> left, List<SourceRef> right) {
        return normalized(left).equals(normalized(right));
    }

    private static List<String> normalized(List<SourceRef> refs) {
        List<String> lines = new ArrayList<>();
        for (SourceRef ref : refs) {
            lines.add(ref.documentId().toLowerCase(java.util.Locale.ROOT) + ":" + (ref.sourceHash() == null ? "-" : ref.sourceHash()));
        }
        lines.sort(Comparator.naturalOrder());
        return lines;
    }
}