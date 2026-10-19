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
        boolean[] protectedCharacters = new boolean[answer.length()];
        protect(Parser.builder().build().parse(answer), protectedCharacters);
        Matcher marker = MARKER.matcher(answer);
        StringBuilder result = new StringBuilder();
        while (marker.find()) {
            if (protectedCharacters[marker.start()] || escaped(answer, marker.start())) continue;
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

    private static boolean escaped(String answer, int position) {
        int slashes = 0;
        while (position > 0 && answer.charAt(--position) == '\\') slashes++;
        return slashes % 2 != 0;
    }
}
