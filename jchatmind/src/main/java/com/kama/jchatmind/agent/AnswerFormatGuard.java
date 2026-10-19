package com.kama.jchatmind.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reject the observed tool-serialization spill without rewriting answers or inventing citations. */
final class AnswerFormatGuard {
    private static final Pattern PARAMETER_SPILL = Pattern.compile(
            "</(?:answer|parameter)>\\s*<parameter\\s+name\\s*=\\s*[\"']citationIds[\"']\\s*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

    private AnswerFormatGuard() { }

    static void verify(String answer) {
        if (PARAMETER_SPILL.matcher(outsideCode(answer)).find()) {
            throw new InvalidAnswerFormatException();
        }
    }

    // Markdown examples may legitimately describe this protocol. Ignore fenced
    // blocks and matched inline code spans; inspect the surrounding prose only.
    private static String outsideCode(String answer) {
        StringBuilder prose = new StringBuilder();
        String fence = null;
        for (String line : answer.split("\\R", -1)) {
            Matcher marker = FENCE.matcher(line);
            if (fence != null) {
                if (marker.matches() && marker.group(1).charAt(0) == fence.charAt(0)
                        && marker.group(1).length() >= fence.length() && marker.group(2).isBlank()) {
                    fence = null;
                }
            } else if (marker.matches()) {
                fence = marker.group(1);
                prose.append("\n[code]\n");
            } else {
                prose.append(line).append('\n');
            }
        }
        String text = prose.toString();
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
