package com.kama.jchatmind.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 正文引用编号必须落在本轮已核验来源的范围内。
 *
 * <p>真实运行里出现过「正文写 [1][2][3]、本轮只有两条甚至零条来源」的回答。那不是排版问题，
 * 而是把不存在的依据展示给访客，因此按无效交付处理。</p>
 *
 * <p>同时保证不是引用标记的方括号不被误杀：数组下标、代码块、行内代码、转义示例与链接。</p>
 */
class AnswerFormatGuardTest {

    @Test
    @DisplayName("编号都落在已核验来源内时放行")
    void markersWithinVerifiedSourcesPass() {
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("一句话结论 [1]\n\n- 要点一 [2]", 2));
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("单独一条来源 [1]", 1));
    }

    @Test
    @DisplayName("编号超出已核验来源条数时拒绝，文案与编号校验一致")
    void markerBeyondVerifiedSourcesIsRejected() {
        InvalidAnswerFormatException error = assertThrows(InvalidAnswerFormatException.class,
                () -> AnswerFormatGuard.verify("结论 [1]，细节 [3]", 2));
        assertEquals("Model answer references a source that could not be verified", error.getMessage());
    }

    @Test
    @DisplayName("零已核验来源时，正文里的引用编号一律视为伪造")
    void markersWithoutVerifiedSourcesAreRejected() {
        InvalidAnswerFormatException error = assertThrows(InvalidAnswerFormatException.class,
                () -> AnswerFormatGuard.verify("结论一 [1]，要点二 [2]。", 0));
        assertEquals("Model answer references a source that could not be verified", error.getMessage());
        // 数组下标不是引用标记，零来源时也应放行。
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("普通答复，数组元素 array[1]，选项编号 2。", 0));
    }

    @Test
    @DisplayName("数组下标、代码块、行内代码、转义与链接都不算引用标记")
    void nonCitationBracketsAreIgnored() {
        String answer = String.join("\n",
                "正文说明 [9]。",
                "",
                "```java",
                "array[1]; // [99]",
                "```",
                "",
                "    array[1]; // indented code [99]",
                "",
                "行内代码 `array[1]` 与转义 \\[9]；链接 [文档](https://example.test/[9])。",
                "",
                "[ref]: https://example.test/[9]");
        // 只有第一行的 [9] 是真标记：两条来源时超范围 → 拒绝。
        assertThrows(InvalidAnswerFormatException.class, () -> AnswerFormatGuard.verify(answer, 2));
        // 九条来源时它在范围内 → 放行（代码块里的 [99] 仍然不参与判断）。
        assertDoesNotThrow(() -> AnswerFormatGuard.verify(answer, 9));
    }

    @Test
    @DisplayName("工具序列化残片仍然拒绝，且保留原本文案")
    void parameterSpillIsStillRejected() {
        InvalidAnswerFormatException error = assertThrows(InvalidAnswerFormatException.class,
                () -> AnswerFormatGuard.verify("答案正文</answer><parameter name=\"citationIds\">[\"ret-1:1\"]"));
        assertEquals("Model answer contains an invalid tool parameter boundary", error.getMessage());
    }

    @Test
    @DisplayName("中文正文紧邻的编号仍是引用，不能当成数组下标放行")
    void markerGluedToChineseTextIsStillACitation() {
        InvalidAnswerFormatException error = assertThrows(InvalidAnswerFormatException.class,
                () -> AnswerFormatGuard.verify("结论[1]。", 0));
        assertEquals("Model answer references a source that could not be verified", error.getMessage());
        assertThrows(InvalidAnswerFormatException.class, () -> AnswerFormatGuard.verify("结论[1]。", 0));
        // 有来源时同样放行，说明判定不是「见到中文就拒绝」。
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("结论[1]。", 1));
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("要点[1][2]。", 2));
    }

    @Test
    @DisplayName("相邻编号逐个校验，第二个超范围同样拒绝")
    void adjacentMarkersAreEachChecked() {
        // 只有一条来源时 [2] 是伪造的，不能因为紧跟着 [1] 就被跳过。
        assertThrows(InvalidAnswerFormatException.class, () -> AnswerFormatGuard.verify("结论 [1][2]。", 1));
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("结论 [1][2]。", 2));
    }

    @Test
    @DisplayName("长编号与超大编号都按越界处理，不会溢出成放行")
    void longMarkersAreStillRejected() {
        assertThrows(InvalidAnswerFormatException.class, () -> AnswerFormatGuard.verify("结论 [1000]。", 0));
        assertThrows(InvalidAnswerFormatException.class, () -> AnswerFormatGuard.verify("结论 [1000]。", 3));
        assertThrows(InvalidAnswerFormatException.class,
                () -> AnswerFormatGuard.verify("结论 [12345678901]。", 3));
        // [0] 不是合法引用位置。
        assertThrows(InvalidAnswerFormatException.class, () -> AnswerFormatGuard.verify("结论 [0]。", 3));
    }

    @Test
    @DisplayName("Markdown 认定的链接整段保护，包括折叠式、快捷式与带方括号的链接文本")
    void markdownLinksAreProtected() {
        // 受保护区域一律用超范围编号（99），零来源时才可能暴露误拦。
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("查看 [1](https://example.test/[99])。", 0));
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("查看 [文档](https://example.test/[99])。", 0));
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("查看 [[99]](https://example.test)。", 0));
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("见 <https://example.test/[99]>。", 0));
        // 已定义的引用式链接：全引用、折叠式、快捷式三种写法都受保护。
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("查看 [1][ref]。\n\n[ref]: https://example.test/[99]", 0));
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("查看 [99][]。\n\n[99]: https://example.test", 0));
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("查看 [99]。\n\n[99]: https://example.test", 0));
        // 但正文里同一编号仍然要被拦住，证明不是整篇放行。
        assertThrows(InvalidAnswerFormatException.class,
                () -> AnswerFormatGuard.verify("查看 [1](https://example.test/[99])，另见 [99]。", 0));
    }

    @Test
    @DisplayName("没有定义的引用式标签、尖括号与偶数反斜杠都不能绕过校验")
    void undefinedReferenceAndLooseBracketsStillReject() {
        // [missing] 没有定义，Flexmark 不认为它是链接，里面的 [1] 仍是正文编号。
        assertThrows(InvalidAnswerFormatException.class,
                () -> AnswerFormatGuard.verify("结论[1][missing]。", 0));
        // <[1]> 不是自动链接（自动链接要求 URI 或邮箱），不能整段屏蔽。
        assertThrows(InvalidAnswerFormatException.class, () -> AnswerFormatGuard.verify("结论 <[1]>。", 0));
        // 两个真实反斜杠＝一个字面反斜杠，编号没有被转义。
        assertThrows(InvalidAnswerFormatException.class, () -> AnswerFormatGuard.verify("结论 \\\\[1]。", 0));
        // 一个反斜杠才是转义示例，按 Markdown 语义跳过。
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("结论 \\[1]。", 0));
    }

    @Test
    @DisplayName("带空白的引用组与范围写法都要核验，与重排器同一份表达式")
    void spacedGroupsAndRangesAreChecked() {
        // 模型常写 `[1, 2]`；上一版守卫只认紧邻逗号，导致零来源时被放行并落库。
        assertThrows(InvalidAnswerFormatException.class, () -> AnswerFormatGuard.verify("结论 [1, 2]。", 0));
        assertThrows(InvalidAnswerFormatException.class, () -> AnswerFormatGuard.verify("结论 [1， 2]。", 0));
        assertThrows(InvalidAnswerFormatException.class, () -> AnswerFormatGuard.verify("结论 [1, 2]。", 1));
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("结论 [1, 2]。", 2));
        // 范围写法不在工具契约里：来源再多也按非法位置拒绝，不能因为解析失败就放行。
        assertThrows(InvalidAnswerFormatException.class, () -> AnswerFormatGuard.verify("结论 [1-2]。", 5));
        assertThrows(InvalidAnswerFormatException.class, () -> AnswerFormatGuard.verify("结论 [1–2]。", 5));
    }

    @Test
    @DisplayName("多维数组下标不算引用，紧邻的引用仍然算")
    void subscriptChainsVersusAdjacentCitations() {
        assertDoesNotThrow(() -> AnswerFormatGuard.verify("矩阵 array[1][2] 与 items[0][3]。", 0));
        assertThrows(InvalidAnswerFormatException.class,
                () -> AnswerFormatGuard.verify("矩阵 array[1][2]，结论 [1]。", 0));
    }
}
