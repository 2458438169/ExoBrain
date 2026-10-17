package com.kama.jchatmind.retrieval;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code legacy-title-v1} 写入路径：与基线完全相同的语义——每个章节标题一个向量，chunk 只保存正文，
 * {@code revision_id} 为 NULL（= legacy 行），metadata 为 NULL，位置列与源 hash 为 NULL
 * （不伪造位置，界面只能打开整份文档）。
 *
 * <p>只在“无状态行但已有 legacy chunk”的旧库上使用：这样新上传不会把旧库静默切换到 body-v1；
 * 迁移到正文索引必须显式重建。</p>
 */
@Service
@Slf4j
public class LegacyTitleIndexer {

    private final MarkdownParserService parser;
    private final RagService rag;
    private final ChunkBgeM3Mapper chunks;

    public LegacyTitleIndexer(MarkdownParserService parser, RagService rag, ChunkBgeM3Mapper chunks) {
        this.parser = parser;
        this.rag = rag;
        this.chunks = chunks;
    }

    /**
     * @return 写入的 chunk 数（0 表示没有可索引的章节，由调用方决定如何处理）。
     *
     * <p>不做 id 规范化：这条路径要与基线逐字一致（调用方传入的就是数据库里的 id），
     * 额外的格式校验只会让基线行为的调用方失败。</p>
     */
    public int index(String kbId, String documentId, byte[] raw) {
        String canonicalKb = kbId;
        String canonicalDocument = documentId;
        List<MarkdownParserService.MarkdownSection> sections =
                parser.parseMarkdown(new ByteArrayInputStream(raw));
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (MarkdownParserService.MarkdownSection section : sections) {
            String title = section.getTitle();
            if (title == null || title.isBlank()) {
                continue;
            }
            ChunkBgeM3 chunk = ChunkBgeM3.builder()
                    .kbId(canonicalKb)
                    .docId(canonicalDocument)
                    .content(section.getContent() == null ? "" : section.getContent())
                    .metadata(null)
                    .embedding(rag.embed(canonicalKb, title))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            if (chunks.insert(chunk) <= 0) {
                throw new IndexingException("写入 legacy 索引失败，本次上传已取消");
            }
            count++;
        }
        log.info("legacy-title-v1 索引写入完成: kbId={}, documentId={}, chunks={}", canonicalKb,
                canonicalDocument, count);
        return count;
    }
}