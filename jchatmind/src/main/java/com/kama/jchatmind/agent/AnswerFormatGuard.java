package com.kama.jchatmind.agent;

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
    // 编号表达式与位置解析统一放在 {@link CitationMarkers}：上一版两个类各写一份，
    // 守卫漏掉了带空白的 `[1, 2]`、`[1， 2]` 与范围写法。

    private AnswerFormatGuard() { }

    /** 只做格式校验，供既有调用点使用。 */
    static void verify(String answer) {
        boolean[] codeCharacters = MarkdownRegions.codeCharacters(answer);
        Matcher matcher = PARAMETER_SPILL.matcher(answer);
        while (matcher.find()) {
            if (!codeCharacters[matcher.start()]) {
                throw new InvalidAnswerFormatException();
            }
        }
    }

    /**
     * 格式校验 + 引用编号核对。
     *
     * <p>{@code citationCount} 是这一轮真正通过服务端校验的来源条数，也就是正文允许出现的最大编号。
     * 实测里出现过正文写 {@code [1][2][3]}、却只有两条（甚至零条）已核验来源的回答——那不是排版问题，
     * 而是把不存在的依据展示给访客，因此按无效交付处理，与「无效引用失败且不落库」保持一致。</p>
     *
     * <p>哪些方括号不算引用，与 {@link AnswerCitationFormatter} 共用同一份 Markdown 判定
     * （{@link MarkdownRegions}）：代码、链接与图片、**已定义**的引用式链接、自动链接、链接定义、内联 HTML，
     * 以及转义示例。没有定义的引用式链接（{@code [1][missing]}）不是链接，里面的编号照样要核验；
     * 中文正文紧邻的 {@code 结论[1]} 也算引用——中文不是标识符，把它当数组下标会留下「零来源也放行」的漏洞。</p>
     */
    static void verify(String answer, int citationCount) {
        verify(answer);
        int allowed = Math.max(0, citationCount);
        boolean[] protectedCharacters = MarkdownRegions.protectedCharacters(answer);
        Matcher matcher = CitationMarkers.PATTERN.matcher(answer);
        int previousEnd = -1;
        boolean previousWasCitation = false;
        while (matcher.find()) {
            boolean citation = !protectedCharacters[matcher.start()]
                    && !MarkdownRegions.escaped(answer, matcher.start())
                    && isNotSubscript(answer, matcher.start(), previousEnd, previousWasCitation);
            if (citation) {
                for (Integer index : CitationMarkers.positions(matcher.group(1))) {
                    if (index < 1 || index > allowed) {
                        // 与 AnswerCitationFormatter 的编号校验用同一句文案：日志里要能一眼看出
                        // 是「编号没有可核验来源」，而不是被误读成工具参数残片。
                        throw new InvalidAnswerFormatException(
                                "Model answer references a source that could not be verified");
                    }
                }
            }
            previousEnd = matcher.end();
            previousWasCitation = citation;
        }
    }

    /**
     * 紧跟 ASCII 标识符的方括号是下标（{@code array[1]}），其余算引用。
     *
     * <p>紧邻的 {@code [1][2]} 逐个判定：前一组若是引用，这一组也是引用；前一组若是下标
     * （{@code array[1][2]}），这一组也按下标处理。</p>
     */
    private static boolean isNotSubscript(String answer, int start, int previousEnd, boolean previousWasCitation) {
        if (start == 0) return true;
        char before = answer.charAt(start - 1);
        if (isIdentifierChar(before)) return false;
        if (before == ']' && previousEnd == start) return previousWasCitation;
        return true;
    }

    private static boolean isIdentifierChar(char c) {
        return c == '_' || c == '$'
                || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}