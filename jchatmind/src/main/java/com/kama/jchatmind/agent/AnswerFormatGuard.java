package com.kama.jchatmind.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 拒绝已知的工具序列化残片，并核对正文里的引用编号是否真的存在。
 *
 * <p>它不改写正文，也不补造引用：要么原样放行，要么整轮判为无效交付（上层据此记 FAILED 且不落库正文）。</p>
 */
final class AnswerFormatGuard {
    private static final Pattern PARAMETER_SPILL = Pattern.compile(
            "</(?:answer|parameter)>\\s*<parameter\\s+name\\s*=\\s*[\"']citationIds[\"']\\s*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    // 位数不设上限：`[1000]`、`[12345678901]` 这类越界编号必须被识别出来，而不是当成不存在。
    private static final Pattern MARKER = Pattern.compile("\\[(\\d+)\\]");
    // 受保护区域：引用式链接定义、链接目标、自动链接、裸 URL 里的方括号都不是引用。
    private static final Pattern LINK_DEFINITION = Pattern.compile("^ {0,3}\\[[^\\]]+\\]:.*$", Pattern.MULTILINE);
    private static final Pattern INLINE_LINK = Pattern.compile("\\[[^\\[\\]]*\\]\\([^)]*\\)");
    private static final Pattern AUTOLINK = Pattern.compile("<[^<>\\s]+>");
    private static final Pattern BARE_URL =
            Pattern.compile("(?i)(?:https?|ftp)://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    private AnswerFormatGuard() { }

    /** 只做格式校验，供既有调用点使用。 */
    static void verify(String answer) {
        if (PARAMETER_SPILL.matcher(outsideCode(answer)).find()) {
            throw new InvalidAnswerFormatException();
        }
    }

    /**
     * 格式校验 + 引用编号核对。
     *
     * <p>{@code citationCount} 是这一轮真正通过服务端校验的来源条数，也就是正文允许出现的最大编号。
     * 实测里出现过正文写 {@code [1][2][3]}、却只有两条（甚至零条）已核验来源的回答——那不是排版问题，
     * 而是把不存在的依据展示给访客，因此按无效交付处理，与「无效引用失败且不落库」保持一致。</p>
     *
     * <p>只检查真正像引用的标记：代码块（围栏与缩进）、行内代码、转义示例、链接文本与目标、链接定义、
     * 自动链接与裸 URL、以及数组下标 {@code array[1]} 都不算。中文正文紧邻的 {@code 结论[1]} 算引用——
     * 中文不是标识符字符，把它当数组下标会留下「零来源也被放行」的漏洞。</p>
     */
    static void verify(String answer, int citationCount) {
        verify(answer);
        // 正文里的编号必须能在本轮已核验来源里找到对应项；一条来源都没有时，任何编号都是伪造的。
        int allowed = Math.max(0, citationCount);
        for (int index : citationMarkers(outsideCode(answer))) {
            if (index < 1 || index > allowed) {
                // 与 AnswerCitationFormatter 的编号校验用同一句文案：日志里要能一眼看出
                // 是「编号没有可核验来源」，而不是被误读成工具参数残片。
                throw new InvalidAnswerFormatException("Model answer references a source that could not be verified");
            }
        }
    }

    private static List<Integer> citationMarkers(String prose) {
        String text = maskProtectedRegions(prose);
        List<Integer> markers = new ArrayList<>();
        Matcher matcher = MARKER.matcher(text);
        int previousEnd = -1;
        boolean previousWasCitation = false;
        while (matcher.find()) {
            boolean citation = isCitationMarker(text, matcher.start(), matcher.end(), previousEnd, previousWasCitation);
            if (citation) {
                markers.add(parseIndex(matcher.group(1)));
            }
            previousEnd = matcher.end();
            previousWasCitation = citation;
        }
        return markers;
    }

    /** 超长编号一律按「远超本轮来源数」处理，避免溢出异常把校验变成放行。 */
    private static int parseIndex(String digits) {
        if (digits.length() > 9) {
            return Integer.MAX_VALUE;
        }
        return Integer.parseInt(digits);
    }

    private static boolean isCitationMarker(String prose, int start, int end, int previousEnd, boolean previousWasCitation) {
        char before = start > 0 ? prose.charAt(start - 1) : '\n';
        char after = end < prose.length() ? prose.charAt(end) : '\n';
        if (before == '\\') return false;                                   // 转义示例 \[1]
        if (after == '(') return false;                                     // [1](https://…) 链接文本
        if (after == ':') return false;                                     // [1]: https://… 链接定义
        // 引用式链接的文本：[1][ref] —— 后面跟的是非纯数字标签就不是引用。
        if (after == '[' && hasReferenceLabel(prose, end)) return false;
        // 紧跟 ASCII 标识符的方括号是下标/索引：array[1]、items[2]、x[0]。
        // 只认 ASCII：中文是字母，但「结论[1]」是引用而不是下标。
        if (isIdentifierChar(before)) return false;
        // 紧邻的 `[1][2]`：前一组若已是引用，这一组也是引用；前一组若是下标（array[1][2]），这一组也是下标。
        if (before == ']' && previousEnd == start) return previousWasCitation;
        return before != ']';
    }

    private static boolean hasReferenceLabel(String prose, int end) {
        int close = prose.indexOf(']', end + 1);
        if (close < 0) return false;
        String label = prose.substring(end + 1, close);
        if (label.isEmpty()) return false;                                  // [1][] 折叠式，按引用处理
        for (int i = 0; i < label.length(); i++) {
            if (!Character.isDigit(label.charAt(i))) return true;
        }
        return false;                                                       // [1][2] 是相邻引用
    }

    private static boolean isIdentifierChar(char c) {
        return c == '_' || c == '$'
                || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    // 链接目标、裸 URL、自动链接、链接定义里的方括号都不是正文引用，先整段屏蔽再扫描。
    private static String maskProtectedRegions(String prose) {
        String text = LINK_DEFINITION.matcher(prose).replaceAll("\n");
        text = AUTOLINK.matcher(text).replaceAll(" [code] ");
        text = INLINE_LINK.matcher(text).replaceAll(" [code] ");
        return BARE_URL.matcher(text).replaceAll(" [code] ");
    }

    // Markdown examples may legitimately describe this protocol. Ignore fenced
    // blocks, indented code blocks and matched inline code spans; inspect the surrounding prose only.
    private static String outsideCode(String answer) {
        StringBuilder prose = new StringBuilder();
        String fence = null;
        boolean indentCode = false;
        boolean previousBlank = true;
        for (String line : answer.split("\\R", -1)) {
            Matcher marker = FENCE.matcher(line);
            if (fence != null) {
                if (marker.matches() && marker.group(1).charAt(0) == fence.charAt(0)
                        && marker.group(1).length() >= fence.length() && marker.group(2).isBlank()) {
                    fence = null;
                }
                previousBlank = line.isBlank();
                continue;
            }
            if (marker.matches()) {
                fence = marker.group(1);
                prose.append("\n[code]\n");
                indentCode = false;
                previousBlank = false;
                continue;
            }
            boolean indented = line.startsWith("    ") || line.startsWith("\t");
            if (indentCode) {
                if (line.isBlank()) {
                    prose.append('\n');
                    previousBlank = true;
                    continue;
                }
                if (indented) continue;                         // 仍在缩进代码块里
                indentCode = false;                             // 缩进代码块结束，本行是正文
            } else if (indented && previousBlank) {
                indentCode = true;                              // 缩进代码块开始
                continue;
            }
            prose.append(line).append('\n');
            previousBlank = line.isBlank();
        }
        return insideCodeSpans(prose.toString());
    }

    private static String insideCodeSpans(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length();) {
            if (text.charAt(i) != '`') {
                result.append(text.charAt(i++));
                continue;
            }
            int start = i;
            while (i < text.length() && text.charAt(i) == '`') i++;
            int length = i - start;
            int closing = text.indexOf('`', i);
            boolean matched = false;
            while (closing >= 0) {
                int end = closing;
                while (end < text.length() && text.charAt(end) == '`') end++;
                if (end - closing == length) {
                    result.append(" [code] ");
                    i = end;
                    matched = true;
                    break;
                }
                closing = text.indexOf('`', end);
            }
            if (!matched) result.append(text, start, i);
        }
        return result.toString();
    }
}