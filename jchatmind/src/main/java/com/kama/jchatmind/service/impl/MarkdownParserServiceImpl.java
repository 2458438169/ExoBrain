package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.MarkdownParserService;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 解析服务。
 *
 * <p>并发约定：本类是 Spring 单例，因此不允许在实例字段中保存请求状态。
 * 上传的正文只以局部不可变变量的形式在单次调用内传递，AST 与提取过程同样限定在本次调用内，
 * 也不使用 static 字段、共享 ThreadLocal 或方法级 synchronized 来掩盖共享状态。</p>
 *
 * <p>关于 flexmark Parser 实例的复用（flexmark-all 0.64.8，本地 ~/.m2 只有二进制包，
 * 因此直接核对 jar 内字节码，而不是仅凭“字段是 final”推断线程安全）：</p>
 * <ul>
 *   <li>{@code com.vladsch.flexmark.parser.Parser} 的实例字段全部为 final，
 *       {@code parse(String)} / {@code parse(BasedSequence)} / {@code parseReader(Reader)} / {@code postProcess}
 *       中只有 getfield 读取，没有 putfield，也没有 monitorenter/monitorexit。</li>
 *   <li>但 Parser 持有的配置 DataHolder 会在首次解析时把“计算出的默认值”写回自身：
 *       实测一次 parse 之后 {@code parser.getOptions()} 多出
 *       {@code DataKey<EmptyList> SPECIAL_LEAD_IN_HANDLERS}，而
 *       {@code MutableDataSet.getOrCompute} 的写回目标是普通 {@code java.util.HashMap}，没有任何同步。</li>
 *   <li>因此共享同一个 Parser 会让并发解析同时写同一张 HashMap，官方也没有给出线程安全承诺。
 *       这里改为每次调用新建 Parser（实测构建约 0.063ms/次），配置与请求状态都不跨调用共享；
 *       单次调用的可变状态（DocumentParser、Document 及其引用仓库）由 flexmark 在 parse() 内部按调用创建。</li>
 * </ul>
 */
@Service
@Slf4j
public class MarkdownParserServiceImpl implements MarkdownParserService {

    @Override
    public List<MarkdownSection> parseMarkdown(InputStream inputStream) {
        try {
            // 请求正文只在本次调用内使用，不再写入实例字段。
            String originalMarkdownContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

            // 每次调用使用独立的解析器实例，避免跨请求共享 flexmark 内部的配置缓存。
            Document document = newParser().parse(originalMarkdownContent);

            // 提取标题和内容
            List<MarkdownSection> sections = new ArrayList<>();
            extractSections(document, originalMarkdownContent, sections);

            log.info("解析 Markdown 完成，共提取 {} 个章节", sections.size());
            return sections;
        } catch (Exception e) {
            log.error("解析 Markdown 失败", e);
            throw new RuntimeException("解析 Markdown 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建仅服务于一次解析调用的解析器。
     *
     * <p>配置与基线保持一致：没有显式注册任何 extension。因此 GFN 风格的表格文本目前
     * 仍按普通段落解析，{@link TableBlock} 分支不会被触发；若后续任务启用
     * {@code TablesExtension}，表格章节的内容会从“纯文本”变成“原始表格 Markdown”，
     * 属于需要单独评估的解析行为变更。</p>
     */
    static Parser newParser() {
        MutableDataSet options = new MutableDataSet();
        return Parser.builder(options).build();
    }

    /**
     * 提取标题和内容
     * 只遍历文档的直接子节点，遇到任何标题就停止收集当前标题的内容
     *
     * @param originalMarkdownContent 本次调用的原文，用于按 AST 偏移切片取回原始 Markdown
     */
    private void extractSections(Document document, String originalMarkdownContent, List<MarkdownSection> sections) {
        // 收集文档的所有直接子节点（顶层节点）
        List<Node> topLevelNodes = new ArrayList<>();
        Node child = document.getFirstChild();
        while (child != null) {
            topLevelNodes.add(child);
            child = child.getNext();
        }
        
        // 遍历顶层节点，找到所有标题
        for (int i = 0; i < topLevelNodes.size(); i++) {
            Node node = topLevelNodes.get(i);
            
            if (node instanceof Heading) {
                Heading heading = (Heading) node;
                String title = extractHeadingText(heading);
                
                if (title == null || title.trim().isEmpty()) {
                    continue;
                }
                
                // 收集当前标题到下一个标题（任何级别）之间的所有内容
                StringBuilder contentBuilder = new StringBuilder();
                for (int j = i + 1; j < topLevelNodes.size(); j++) {
                    Node nextNode = topLevelNodes.get(j);
                    
                    // 如果遇到任何标题，停止收集
                    if (nextNode instanceof Heading) {
                        break;
                    }
                    
                    // 提取节点内容
                    String content = extractNodeContent(nextNode, originalMarkdownContent);
                    if (content != null && !content.trim().isEmpty()) {
                        if (contentBuilder.length() > 0) {
                            contentBuilder.append("\n");
                        }
                        contentBuilder.append(content);
                    }
                }
                
                String content = contentBuilder.toString().trim();
                sections.add(new MarkdownSection(title, content));
            }
        }
    }

    /**
     * 提取标题文本
     */
    private String extractHeadingText(Heading heading) {
        StringBuilder text = new StringBuilder();
        Node child = heading.getFirstChild();
        while (child != null) {
            String childText = extractPlainText(child);
            if (childText != null && !childText.trim().isEmpty()) {
                if (text.length() > 0) {
                    text.append(" ");
                }
                text.append(childText);
            }
            child = child.getNext();
        }
        return text.toString().trim();
    }

    /**
     * 提取节点内容（保留格式，特别是表格）
     *
     * @param originalMarkdownContent 本次调用的原文
     */
    private String extractNodeContent(Node node, String originalMarkdownContent) {
        if (node == null) {
            return null;
        }
        
        // 对于表格，保留原始 Markdown 格式
        if (node instanceof TableBlock) {
            return extractTableMarkdown(node, originalMarkdownContent);
        }
        
        // 对于其他节点，提取文本内容
        return extractPlainText(node);
    }

    /**
     * 提取表格的原始 Markdown 格式
     *
     * @param originalMarkdownContent 本次调用的原文，表格偏移量相对该原文
     */
    private String extractTableMarkdown(Node tableNode, String originalMarkdownContent) {
        if (originalMarkdownContent == null) {
            return extractPlainText(tableNode);
        }
        
        try {
            // 获取表格节点在原始文档中的位置
            BasedSequence chars = tableNode.getChars();
            if (chars != null && chars.length() > 0) {
                int startOffset = chars.getStartOffset();
                int endOffset = chars.getEndOffset();
                
                // 从原始 Markdown 中提取表格内容
                if (startOffset >= 0 && endOffset <= originalMarkdownContent.length() && startOffset < endOffset) {
                    String tableMarkdown = originalMarkdownContent.substring(startOffset, endOffset);
                    return tableMarkdown.trim();
                }
            }
            
            // 如果无法从原始内容提取，尝试从节点本身提取
            return extractPlainText(tableNode);
        } catch (Exception e) {
            log.warn("提取表格 Markdown 失败，使用文本提取: {}", e.getMessage());
            return extractPlainText(tableNode);
        }
    }

    /**
     * 提取节点的纯文本内容
     */
    private String extractPlainText(Node node) {
        if (node == null) {
            return null;
        }
        
        StringBuilder text = new StringBuilder();
        extractTextRecursive(node, text);
        return text.length() > 0 ? text.toString().trim() : null;
    }

    /**
     * 递归提取文本
     */
    private void extractTextRecursive(Node node, StringBuilder text) {
        if (node == null) {
            return;
        }
        
        // 跳过标题节点（标题已经在 extractSections 中单独处理）
        if (node instanceof Heading) {
            return;
        }
        
        // 对于有子节点的节点，递归处理子节点
        Node child = node.getFirstChild();
        if (child != null) {
            boolean isFirstChild = true;
            while (child != null) {
                // 在子节点之间添加适当的分隔符
                if (!isFirstChild && text.length() > 0) {
                    // 检查是否需要换行
                    if (child instanceof Block) {
                        if (!text.toString().endsWith("\n")) {
                            text.append("\n");
                        }
                    } else {
                        text.append(" ");
                    }
                }
                extractTextRecursive(child, text);
                child = child.getNext();
                isFirstChild = false;
            }
        } else {
            // 叶子节点，尝试提取文本
            try {
                BasedSequence chars = node.getChars();
                if (chars != null && chars.length() > 0) {
                    String nodeText = chars.toString().trim();
                    if (!nodeText.isEmpty()) {
                        if (text.length() > 0 && !text.toString().endsWith("\n")) {
                            text.append(" ");
                        }
                        text.append(nodeText);
                    }
                }
            } catch (Exception e) {
                // 忽略，继续处理
            }
        }
    }
}
