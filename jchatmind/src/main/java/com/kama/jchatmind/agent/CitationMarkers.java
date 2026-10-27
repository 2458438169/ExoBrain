package com.kama.jchatmind.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 正文引用编号的表达式与位置解析，供重排（{@link AnswerCitationFormatter}）与校验
 * （{@link AnswerFormatGuard}）共用。
 *
 * <p>上一版两个类各写一份：重排器允许逗号两侧空白（模型常写 {@code [1, 2]}），守卫只认紧邻逗号，
 * 于是零来源时 {@code [1, 2]}、{@code [1， 2]} 被放行并落库。表达式与解析规则现在只有这一份。</p>
 *
 * <p>逗号分组是明确的位置；范围写法（{@code -} / {@code –}）不在工具契约里，按非法位置拒绝，
 * 与重排器原有行为保持一致。</p>
 */
final class CitationMarkers {
    static final Pattern PATTERN = Pattern.compile("\\[([0-9]+(?:\\s*[,，–-]\\s*[0-9]+)*)\\]");

    private CitationMarkers() { }

    /** 解析一组位置；范围、非数字或溢出一律按非法位置拒绝。 */
    static List<Integer> positions(String group) {
        List<Integer> positions = new ArrayList<>();
        for (String token : group.split("[,，]")) {
            try {
                positions.add(Integer.parseInt(token.strip()));
            } catch (NumberFormatException invalid) {
                throw new InvalidAnswerFormatException("Model answer contains an invalid citation position");
            }
        }
        return positions;
    }
}