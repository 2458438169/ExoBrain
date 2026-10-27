package com.kama.jchatmind.agent;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Align prose references with the registry's verified, sorted and deduplicated source labels. */
final class AnswerCitationFormatter {
    private static final Pattern MARKER = Pattern.compile("\\[([0-9]+(?:\\s*[,，–-]\\s*[0-9]+)*)\\]");

    private AnswerCitationFormatter() { }

    // The model's [n] means the nth citationIds argument, never a retrieval rank.
    // Do not call for ordinary answers without citationIds: their bracketed numbers
    // may be examples or array indices. Only verified mappings can generate labels.
    static String relabel(String answer, Map<Integer, Integer> labelsByRequestIndex) {
        boolean[] protectedCharacters = MarkdownRegions.protectedCharacters(answer);
        Matcher marker = MARKER.matcher(answer);
        StringBuilder result = new StringBuilder();
        while (marker.find()) {
            if (protectedCharacters[marker.start()] || MarkdownRegions.escaped(answer, marker.start())) continue;
            StringBuilder replacement = new StringBuilder();
            // Comma groups are unambiguous positions; ranges are not part of the
            // tool contract and fail closed instead of retaining stale numbers.
            for (String position : marker.group(1).split("[,，]")) {
                Integer label;
                try {
                    label = labelsByRequestIndex.get(Integer.parseInt(position.strip()));
                } catch (NumberFormatException invalid) {
                    throw new InvalidAnswerFormatException("Model answer contains an invalid citation position");
                }
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
