package com.kama.jchatmind.agent;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/** Align prose references with the registry's verified, sorted and deduplicated source labels. */
final class AnswerCitationFormatter {

    private AnswerCitationFormatter() { }

    // The model's [n] means the nth citationIds argument, never a retrieval rank.
    // Do not call for ordinary answers without citationIds: their bracketed numbers
    // may be examples or array indices. Only verified mappings can generate labels.
    static String relabel(String answer, Map<Integer, Integer> labelsByRequestIndex) {
        boolean[] protectedCharacters = MarkdownRegions.protectedCharacters(answer);
        Matcher marker = CitationMarkers.PATTERN.matcher(answer);
        StringBuilder result = new StringBuilder();
        while (marker.find()) {
            if (protectedCharacters[marker.start()] || MarkdownRegions.escaped(answer, marker.start())) continue;
            StringBuilder replacement = new StringBuilder();
            // 位置解析与守卫共用一份：逗号两侧允许空白，范围写法按非法位置拒绝。
            List<Integer> positions = CitationMarkers.positions(marker.group(1));
            for (Integer position : positions) {
                Integer label = labelsByRequestIndex.get(position);
                if (label == null) {
                    throw new InvalidAnswerFormatException("Model answer references a source that could not be verified");
                }
                replacement.append('[').append(label).append(']');
            }
            marker.appendReplacement(result, Matcher.quoteReplacement(replacement.toString()));
        }
        marker.appendTail(result);
        return result.toString();
    }
}