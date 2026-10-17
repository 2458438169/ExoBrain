package com.kama.jchatmind.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 源文本标准化与来源定位（契约 §3）。
 *
 * <p>标准化顺序固定为：UTF-8 解码 → 移除首个可选 BOM（U+FEFF）→ 将 CRLF / CR 统一为 LF。
 * {@link #sha256()} 针对标准化后的文本再以 UTF-8 编码计算，仅用于比对；
 * 原文接口展示的原始换行不受影响。</p>
 *
 * <p>行号基于标准化文本，从 1 开始、两端包含，满足 {@code 1 <= startLine <= endLine <= lineCount()}。
 * 标准化只删除 BOM 与 CR，字符偏移与行结构不会被清洗或拼接破坏，因此 AST 偏移仍能映射回原文行号。</p>
 */
public final class SourceText {

    public static final char BOM = '\uFEFF';

    private final String text;
    private final String sha256;
    private final int[] lineStarts;

    private SourceText(String text, String sha256, int[] lineStarts) {
        this.text = text;
        this.sha256 = sha256;
        this.lineStarts = lineStarts;
    }

    /** 契约 §3 的标准化：UTF-8 解码 → 去首个 BOM → CRLF/CR 统一为 LF。 */
    public static SourceText normalize(byte[] raw) {
        return normalize(new String(raw, StandardCharsets.UTF_8));
    }

    public static SourceText normalize(String decoded) {
        String normalized = decoded;
        if (!normalized.isEmpty() && normalized.charAt(0) == BOM) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replace("\r\n", "\n").replace('\r', '\n');
        return new SourceText(normalized, sha256Hex(normalized), lineStarts(normalized));
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required to identify source text", unavailable);
        }
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        if (!text.isEmpty()) {
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = starts.get(i);
        }
        return result;
    }

    /** 标准化文本（LF 换行）。 */
    public String text() {
        return text;
    }

    /** 标准化文本的 SHA-256（hex，小写）。 */
    public String sha256() {
        return sha256;
    }

    public int length() {
        return text.length();
    }

    /** 总行数；空文本为 0 行。 */
    public int lineCount() {
        return lineStarts.length;
    }

    /**
     * 字符偏移 → 1 基行号。偏移会夹取到合法范围；空文本得到 1（调用方应先判断 {@link #lineCount()}）。
     */
    public int lineOf(int charOffset) {
        if (lineStarts.length == 0) {
            return 1;
        }
        int offset = Math.max(0, Math.min(charOffset, text.length()));
        int low = 0;
        int high = lineStarts.length - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (lineStarts[middle] <= offset) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low + 1;
    }

    /**
     * 字符范围 → 两端包含的行号（契约 §3：{@code 1 <= startLine <= endLine <= lineCount()}）。
     *
     * @param startChar 起始字符偏移（包含）
     * @param endChar   结束字符偏移（不包含；等于起始偏移时表示一个字符宽度）
     */
    public LineRange lineRange(int startChar, int endChar) {
        if (lineStarts.length == 0) {
            return new LineRange(1, 1);
        }
        int start = Math.max(0, Math.min(startChar, text.length()));
        int endExclusive = Math.max(start, Math.min(endChar, text.length()));
        int lastChar = endExclusive > start ? endExclusive - 1 : start;
        int startLine = lineOf(start);
        int endLine = lineOf(lastChar);
        return new LineRange(startLine, Math.max(startLine, endLine));
    }

    /** 两端包含的行范围。 */
    public record LineRange(int startLine, int endLine) {
        public LineRange {
            if (startLine < 1 || endLine < startLine) {
                throw new IllegalArgumentException("invalid line range " + startLine + ".." + endLine);
            }
        }
    }
}
