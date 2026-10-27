package com.kama.jchatmind.agent;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.ImageRef;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.LinkRef;
import com.vladsch.flexmark.ast.MailLink;
import com.vladsch.flexmark.ast.Reference;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;

import java.util.Arrays;

/**
 * Markdown 里「不是正文引用」的字符区间与转义判断，供引用编号的重排与校验共用。
 *
 * <p>以前 {@link AnswerCitationFormatter} 用 Markdown 解析结果、{@link AnswerFormatGuard} 另写正则，
 * 两套语义不一致：守卫会漏掉没有定义的引用式链接（`[1][missing]`）、把 `<[1]>` 当成自动链接整段屏蔽，
 * 又会误拦折叠式/快捷式这类合法链接。这里统一成一份：</p>
 *
 * <ul>
 *   <li>代码（围栏、缩进、行内）、链接与图片、**已定义**的引用式链接、自动链接、邮件链接、
 *       链接定义、内联 HTML 都整段保护；</li>
 *   <li>没有定义的引用式链接不保护——那不是链接，里面的 `[1]` 仍是正文编号；</li>
 *   <li>转义按反斜杠的奇偶判断：`\[1]` 是转义示例，`\\[1]` 是一个字面反斜杠加一个真实编号。</li>
 * </ul>
 */
final class MarkdownRegions {

    private MarkdownRegions() { }

    /** 返回与输入等长的标记数组，true 表示该字符位于不应被当成引用的区域。 */
    static boolean[] protectedCharacters(String answer) {
        boolean[] protectedCharacters = new boolean[answer.length()];
        protect(Parser.builder().build().parse(answer), protectedCharacters);
        return protectedCharacters;
    }

    /** 只保护代码区域：工具残片检测要能看见内联 HTML，但不应命中代码示例。 */
    static boolean[] codeCharacters(String answer) {
        boolean[] protectedCharacters = new boolean[answer.length()];
        protectCode(Parser.builder().build().parse(answer), protectedCharacters);
        return protectedCharacters;
    }

    private static void protectCode(Node node, boolean[] protectedCharacters) {
        if (node instanceof Code || node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
            Arrays.fill(protectedCharacters, node.getStartOffset(), node.getEndOffset(), true);
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            protectCode(child, protectedCharacters);
        }
    }

    /** 反斜杠奇偶：奇数个表示被转义，偶数个表示前面只是字面反斜杠。 */
    static boolean escaped(String answer, int position) {
        int slashes = 0;
        while (position > 0 && answer.charAt(--position) == '\\') {
            slashes++;
        }
        return slashes % 2 != 0;
    }

    private static void protect(Node node, boolean[] protectedCharacters) {
        if (node instanceof Code || node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock
                || node instanceof Link || node instanceof Image || node instanceof ImageRef
                || node instanceof LinkRef reference && reference.isDefined()
                || node instanceof AutoLink || node instanceof MailLink || node instanceof Reference
                || node instanceof HtmlBlock || node instanceof HtmlInline) {
            Arrays.fill(protectedCharacters, node.getStartOffset(), node.getEndOffset(), true);
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            protect(child, protectedCharacters);
        }
    }
}