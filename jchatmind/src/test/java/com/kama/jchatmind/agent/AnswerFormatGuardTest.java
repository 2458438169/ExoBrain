package com.kama.jchatmind.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    @DisplayName("编号超出已核验来源条数时拒绝")
    void markerBeyondVerifiedSourcesIsRejected() {
        assertThrows(InvalidAnswerFormatException.class,
                () -> AnswerFormatGuard.verify("结论 [1]，细节 [3]", 2));
    }

    @Test
    @DisplayName("零已核验来源时，正文里的引用编号一律视为伪造")
    void markersWithoutVerifiedSourcesAreRejected() {
        assertThrows(InvalidAnswerFormatException.class,
                () -> AnswerFormatGuard.verify("结论一 [1]，要点二 [2]。", 0));
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
    @DisplayName("工具序列化残片仍然拒绝（既有行为不变）")
    void parameterSpillIsStillRejected() {
        assertThrows(InvalidAnswerFormatException.class, () -> AnswerFormatGuard.verify(
                "答案正文</answer><parameter name=\"citationIds\">[\"ret-1:1\"]"));
    }
}
