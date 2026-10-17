package com.kama.jchatmind.retrieval;

import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.ArrayList;
import java.util.List;

/**
 * 正文切块器：先用 flexmark AST 求标题层级路径与章节源范围，再按预算切正文。
 *
 * <p>与任务 01 的结论一致：flexmark 的 {@code Parser} 实例会把“计算出的默认值”写回未同步的 HashMap，
 * 因此这里每次调用新建 Parser，不共享解析器状态。</p>
 *
 * <p>预算单位见 {@link ChunkBudget}：512–800 / 64–100 是 <b>estimated token</b>（保守字符估算），
 * 同时受 {@code maxCharacters} 字符硬上限约束。正常段落保持连贯；超长代码块/表格按行有界切分，
 * 单行超长时按字符窗口切分。chunk 文本一律取自原文 offset 区间，行号由 {@link SourceText} 的
 * 行表映射，不是从清洗后的文字长度反推。</p>
 */
public class BodyChunker {

    /** 标题路径进入 Embedding 输入的分隔符。 */
    static final String PATH_SEPARATOR = " > ";

    public List<ChunkDraft> chunk(SourceDocument document, ChunkBudget budget) {
        SourceText source = document.source();
        String text = source.text();
        if (text == null || text.isBlank() || source.lineCount() == 0) {
            return List.of();
        }
        Document ast = newParser().parse(text);
        List<ChunkDraft> drafts = new ArrayList<>();
        for (Segment segment : segments(ast, source)) {
            for (Range range : pack(segment, source, budget)) {
                String content = text.substring(range.startChar, range.endChar);
                if (content.isBlank()) {
                    continue;
                }
                drafts.add(new ChunkDraft(document.documentId(), drafts.size(), segment.headingPath(),
                        range.startLine, range.endLine, source.sha256(), content,
                        embeddingText(segment.headingPath(), content)));
            }
        }
        return List.copyOf(drafts);
    }

    /** 与 {@code MarkdownParserServiceImpl.newParser()} 相同：不注册任何 extension，保持解析行为一致。 */
    static Parser newParser() {
        return Parser.builder(new MutableDataSet()).build();
    }

    static String embeddingText(List<String> headingPath, String content) {
        if (headingPath == null || headingPath.isEmpty()) {
            return content;
        }
        return String.join(PATH_SEPARATOR, headingPath) + "\n" + content;
    }

    /**
     * 只遍历文档的直接子节点（与基线解析器相同的可见范围）：标题自身一行，标题行之后的正文
     * 到下一个标题行之前属于该标题的章节。嵌套标题用层级栈形成路径；子章节正文归属最近的标题，
     * 不会在父章节里重复索引。首个标题之前的正文作为路径为空的章节。
     */
    List<Segment> segments(Document ast, SourceText source) {
        String text = source.text();
        List<Segment> segments = new ArrayList<>();
        List<String> path = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        int contentStart = 0;
        Node node = ast.getFirstChild();
        if (node == null) {
            return List.of();
        }
        while (node != null) {
            if (node instanceof Heading heading) {
                int headingStart = clamp(heading.getStartOffset(), text.length());
                addSegment(segments, path, contentStart, headingStart);
                String title = headingText(heading);
                int level = Math.max(1, heading.getLevel());
                while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
                    levels.remove(levels.size() - 1);
                    path.remove(path.size() - 1);
                }
                if (!title.isEmpty()) {
                    levels.add(level);
                    path.add(title);
                }
                contentStart = lineEndOffset(text, clamp(heading.getEndOffset(), text.length()));
            }
            node = node.getNext();
        }
        addSegment(segments, path, contentStart, text.length());
        return segments;
    }

    private static void addSegment(List<Segment> segments, List<String> path, int start, int end) {
        int from = clamp(start, Integer.MAX_VALUE);
        int to = Math.max(from, end);
        if (from < to) {
            segments.add(new Segment(List.copyOf(path), from, to));
        }
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }

    private static String headingText(Heading heading) {
        Node child = heading.getFirstChild();
        StringBuilder text = new StringBuilder();
        while (child != null) {
            String value = child.getChars().toString().trim();
            if (!value.isEmpty()) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(value);
            }
            child = child.getNext();
        }
        if (text.length() == 0) {
            text.append(heading.getChars().toString().trim());
        }
        return text.toString().trim();
    }

    /** 标题行结束后的偏移（下一行的开头）。 */
    private static int lineEndOffset(String text, int offset) {
        int newline = text.indexOf('\n', Math.max(0, offset));
        return newline < 0 ? text.length() : newline + 1;
    }

    /** 把一个章节切成有界 chunk：按段落成组，超长单元按行、再按字符窗口切分，并带有限重叠。 */
    List<Range> pack(Segment segment, SourceText source, ChunkBudget budget) {
        String text = source.text();
        int from = segment.startOffset;
        int to = Math.min(segment.endOffset, text.length());
        while (from < to && Character.isWhitespace(text.charAt(from))) {
            from++;
        }
        while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
            to--;
        }
        if (from >= to) {
            return List.of();
        }
        List<Atom> atoms = atoms(source, from, to, budget);
        List<Range> ranges = new ArrayList<>();
        int index = 0;
        while (index < atoms.size()) {
            int start = atoms.get(index).startChar;
            int last = index;
            while (last + 1 < atoms.size() && fits(text, start, atoms.get(last + 1).endChar, budget)) {
                last++;
            }
            ranges.add(new Range(start, atoms.get(last).endChar, atoms.get(index).startLine,
                    atoms.get(last).endLine));
            if (last == atoms.size() - 1) {
                break;
            }
            index = nextStart(atoms, index, last, text, budget);
        }
        return ranges;
    }

    /** 下一块的起点：包含结尾若干 atom 作为重叠，但必须严格前进。 */
    private static int nextStart(List<Atom> atoms, int first, int last, String text, ChunkBudget budget) {
        int overlapStart = last;
        while (overlapStart - 1 > first) {
            int candidateStart = atoms.get(overlapStart - 1).startChar;
            int tokens = budget.estimateTokens(text.substring(candidateStart, atoms.get(last).endChar));
            if (tokens > budget.overlapEstimatedTokens()) {
                break;
            }
            overlapStart--;
        }
        return overlapStart > first ? overlapStart : last;
    }

    private static boolean fits(String text, int startChar, int endChar, ChunkBudget budget) {
        if (endChar - startChar > budget.maxCharacters()) {
            return false;
        }
        return budget.estimateTokens(text.substring(startChar, endChar)) <= budget.targetEstimatedTokens();
    }

    /** 段落是一个 atom；超长段落先按行、再按字符窗口拆分。空行只作为段落分隔，不属于任何 atom。 */
    List<Atom> atoms(SourceText source, int from, int to, ChunkBudget budget) {
        String text = source.text();
        List<Atom> atoms = new ArrayList<>();
        int lineStart = from;
        int unitStart = -1;
        int unitEnd = -1;
        int unitFirstLine = -1;
        while (lineStart <= to) {
            int newline = text.indexOf('\n', lineStart);
            int lineEnd = (newline < 0 || newline > to) ? to : newline;
            if (!text.substring(lineStart, lineEnd).isBlank()) {
                if (unitStart < 0) {
                    unitStart = lineStart;
                    unitFirstLine = source.lineOf(lineStart);
                }
                unitEnd = lineEnd;
            } else if (unitStart >= 0) {
                addUnit(atoms, source, unitStart, unitEnd, unitFirstLine, budget);
                unitStart = -1;
            }
            if (lineEnd >= to) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        if (unitStart >= 0) {
            addUnit(atoms, source, unitStart, unitEnd, unitFirstLine, budget);
        }
        return atoms;
    }

    private static void addUnit(List<Atom> atoms, SourceText source, int unitStart, int unitEnd, int firstLine,
                                ChunkBudget budget) {
        String text = source.text();
        if (fits(text, unitStart, unitEnd, budget)) {
            atoms.add(new Atom(unitStart, unitEnd, firstLine, source.lineOf(Math.max(unitStart, unitEnd - 1))));
            return;
        }
        int lineStart = unitStart;
        while (lineStart <= unitEnd) {
            int newline = text.indexOf('\n', lineStart);
            int lineEnd = (newline < 0 || newline > unitEnd) ? unitEnd : newline;
            int line = source.lineOf(lineStart);
            if (fits(text, lineStart, lineEnd, budget)) {
                atoms.add(new Atom(lineStart, lineEnd, line, source.lineOf(Math.max(lineStart, lineEnd - 1))));
            } else {
                int window = Math.max(64, Math.min(budget.targetEstimatedTokens(), budget.maxCharacters()));
                for (int windowStart = lineStart; windowStart < lineEnd; windowStart += window) {
                    int windowEnd = Math.min(lineEnd, windowStart + window);
                    atoms.add(new Atom(windowStart, windowEnd, line, source.lineOf(windowEnd - 1)));
                }
            }
            if (lineEnd >= unitEnd) {
                break;
            }
            lineStart = lineEnd + 1;
        }
    }

    /** 章节源范围：标题路径 + 原文 offset 区间（左含右不含）。 */
    record Segment(List<String> headingPath, int startOffset, int endOffset) {
    }

    /** 不可再分的切块单元（段落、行或字符窗口）。 */
    record Atom(int startChar, int endChar, int startLine, int endLine) {
    }

    /** 一个 chunk 的原文范围（左含右不含）。 */
    record Range(int startChar, int endChar, int startLine, int endLine) {
    }
}