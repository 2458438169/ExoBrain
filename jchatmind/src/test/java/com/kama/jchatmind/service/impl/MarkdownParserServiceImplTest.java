package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.MarkdownParserService.MarkdownSection;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.misc.Extension;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单文档解析语义测试。
 *
 * <p>中文断言使用 {@code \\uXXXX} 转义，避免测试结果依赖源码文件的编码设置；正文本身仍按 UTF-8 字节传入，
 * 因此这些断言同时验证了 UTF-8 输入路径。</p>
 */
class MarkdownParserServiceImplTest {

    private static final String CHINESE_TITLE = "\u6807\u9898\u4e00";
    private static final String CHINESE_BODY = "\u6b63\u6587\u5185\u5bb9";

    private static final String TABLE_DOCUMENT =
            "# T\n\n| a | b |\n| --- | --- |\n| @@T1@@ | @@T2@@ |\n";

    private final MarkdownParserServiceImpl service = new MarkdownParserServiceImpl();

    @Test
    void extractsTitleAndContentKeepingCodeBlocksAndChineseText() {
        List<MarkdownSection> sections = parse("# " + CHINESE_TITLE + "\n\n" + CHINESE_BODY
                + " A @@BODY@@\n\n```java\nint x = 1; // @@CODE@@\n```\n");

        assertEquals(1, sections.size(), () -> "unexpected sections: " + sections);
        assertEquals(CHINESE_TITLE, sections.get(0).getTitle());
        assertEquals(CHINESE_BODY + " A @@BODY@@\nint x = 1; // @@CODE@@", sections.get(0).getContent());
    }

    @Test
    void repeatedTitlesProduceOneSectionPerHeading() {
        List<MarkdownSection> sections = parse("# Same\n\nalpha\n\n# Same\n\nbeta\n");

        assertEquals(2, sections.size(), () -> "unexpected sections: " + sections);
        assertEquals("Same", sections.get(0).getTitle());
        assertEquals("alpha", sections.get(0).getContent());
        assertEquals("Same", sections.get(1).getTitle());
        assertEquals("beta", sections.get(1).getContent());
    }

    @Test
    void crlfInputProducesTheSameSectionsAsLfInput() {
        List<MarkdownSection> crlf = parse("# " + CHINESE_TITLE + "\r\n\r\n" + CHINESE_BODY
                + " A @@BODY@@\r\n\r\n```java\r\nint x = 1;\r\n```\r\n");
        List<MarkdownSection> lf = parse("# " + CHINESE_TITLE + "\n\n" + CHINESE_BODY
                + " A @@BODY@@\n\n```java\nint x = 1;\n```\n");

        assertEquals(titles(lf), titles(crlf));
        assertEquals(contents(lf), contents(crlf));
        assertEquals(List.of(CHINESE_TITLE), titles(crlf));
        assertEquals(List.of(CHINESE_BODY + " A @@BODY@@\nint x = 1;"), contents(crlf));
    }

    @Test
    void emptyAndBlankInputsProduceNoSections() {
        assertTrue(parse("").isEmpty(), "empty input must not create a section");
        assertTrue(parse("\n\n \n\n").isEmpty(), "blank input must not create a section");
    }

    @Test
    void headingWithoutBodyProducesASectionWithEmptyContent() {
        List<MarkdownSection> sections = parse("# only\n");

        assertEquals(1, sections.size(), () -> "unexpected sections: " + sections);
        assertEquals("only", sections.get(0).getTitle());
        assertEquals("", sections.get(0).getContent());
    }

    @Test
    void tableTextIsStillFlattenedIntoAParagraph() {
        List<MarkdownSection> sections = parse(TABLE_DOCUMENT);

        assertEquals(1, sections.size(), () -> "unexpected sections: " + sections);
        assertEquals("T", sections.get(0).getTitle());
        String content = sections.get(0).getContent();
        assertTrue(content.contains("| a | b |"), () -> "table text must stay in the section: " + content);
        assertTrue(content.contains("| @@T1@@ | @@T2@@ |"), () -> "table row must stay in the section: " + content);
        assertFalse(content.contains("| a | b |\n"),
                () -> "a raw TableBlock slice would keep the table line breaks: " + content);
    }

    /**
     * 表格分支可达性：基线没有配置 TablesExtension，因此 {@code TableBlock} 节点不会出现，
     * {@code extractTableMarkdown} 分支在当前生产配置下不可达。启用扩展会改变表格章节内容
     * （从段落文本变成原始表格 Markdown），属于需要单独评估的行为变更，本任务不启用。
     */
    @Test
    void productionConfigurationNeverBuildsTableBlockNodes() {
        Document production = MarkdownParserServiceImpl.newParser().parse(TABLE_DOCUMENT);
        assertEquals(0, tableBlocks(production).size(),
                "no TablesExtension is configured, so the TableBlock branch is unreachable");

        List<Extension> extensions = List.of(TablesExtension.create());
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, extensions);
        Document extended = Parser.builder(options).build().parse(TABLE_DOCUMENT);

        assertEquals(1, tableBlocks(extended).size(),
                "enabling TablesExtension would make the TableBlock branch reachable");
        assertEquals("| a | b |\n| --- | --- |\n| @@T1@@ | @@T2@@ |",
                tableBlocks(extended).get(0).getChars().toString().trim(),
                "with the extension the section content would become the raw table Markdown");
    }

    @Test
    void eachCallBuildsItsOwnFlexmarkParser() {
        assertNotSame(MarkdownParserServiceImpl.newParser(), MarkdownParserServiceImpl.newParser(),
                "no parser instance may be shared between calls");
    }

    @Test
    void noRequestStateSurvivesBetweenCalls() {
        String longDocument = buildLongDocument("@@FIRST@@");
        List<MarkdownSection> first = parse(longDocument);

        assertEquals(1, first.size(), () -> "unexpected sections: " + first);
        assertTrue(first.get(0).getContent().contains("@@FIRST@@"));

        List<MarkdownSection> second = parse("# second\n\n@@SECOND@@\n");
        assertEquals(List.of("second"), titles(second));
        assertEquals(List.of("@@SECOND@@"), contents(second));
        assertFalse(contents(second).get(0).contains("@@FIRST@@"),
                "the previous upload must not leak into a later call");

        assertEquals(titles(first), titles(parse(longDocument)));
        assertEquals(contents(first), contents(parse(longDocument)));
    }

    @Test
    void readFailureIsWrappedWithoutEchoingPrivateContent() {
        String privateBody = "# \u79c1\u6709\u6807\u9898 @@PRIVATE@@\n\n" + CHINESE_BODY + "\n";

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.parseMarkdown(streamFailingAfterHalfOf(privateBody)));

        assertTrue(error.getMessage().startsWith("\u89e3\u6790 Markdown \u5931\u8d25"), () -> error.getMessage());
        assertInstanceOf(IOException.class, error.getCause());
        assertFalse(error.getMessage().contains("@@PRIVATE@@"),
                () -> "the failure message must not echo the private body: " + error.getMessage());
    }

    private List<MarkdownSection> parse(String markdown) {
        return service.parseMarkdown(utf8(markdown));
    }

    private static InputStream utf8(String markdown) {
        return new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8));
    }

    /** Streams the first half of the document and then fails, so the failure happens after content was read. */
    private static InputStream streamFailingAfterHalfOf(String document) {
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        int limit = bytes.length / 2;
        return new InputStream() {
            private int index;

            @Override
            public int read() throws IOException {
                if (index >= limit) {
                    throw new IOException("synthetic read failure");
                }
                return bytes[index++] & 0xff;
            }
        };
    }

    private static String buildLongDocument(String marker) {
        StringBuilder builder = new StringBuilder("# first\n\n").append(marker).append("\n\n");
        for (int i = 0; i < 200; i++) {
            builder.append("filler line ").append(i).append(' ').append(CHINESE_BODY).append("\n\n");
        }
        return builder.toString();
    }

    private static List<String> titles(List<MarkdownSection> sections) {
        return sections.stream().map(MarkdownSection::getTitle).toList();
    }

    private static List<String> contents(List<MarkdownSection> sections) {
        return sections.stream().map(MarkdownSection::getContent).toList();
    }

    private static List<TableBlock> tableBlocks(Document document) {
        List<TableBlock> found = new ArrayList<>();
        collectTableBlocks(document, found);
        return found;
    }

    private static void collectTableBlocks(Node node, List<TableBlock> found) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof TableBlock tableBlock) {
                found.add(tableBlock);
            }
            collectTableBlocks(child, found);
        }
    }
}
