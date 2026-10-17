package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.ChunkBgeM3;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author charon
 * @description 针对表【chunk_bge_m3】的数据库操作Mapper
 * @createDate 2025-12-02 15:44:34
 * @Entity com.kama.jchatmind.model.entity.ChunkBgeM3
 */
@Mapper
public interface ChunkBgeM3Mapper {
    int insert(ChunkBgeM3 chunkBgeM3);

    ChunkBgeM3 selectById(String id);

    int deleteById(String id);

    int updateById(ChunkBgeM3 chunkBgeM3);

    List<ChunkBgeM3> similaritySearch(
            @Param("kbId") String kbId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );

    /**
     * 任务 03 的索引写入：额外写入 revision/来源位置列。
     * legacy 路径传 {@code revisionId = null}、{@code metadata = null}、位置列为 null，旧行语义不变。
     */
    int insertIndexed(ChunkBgeM3 chunkBgeM3);

    /**
     * 结构化检索查询：在 SQL 里限定 KB 与活动索引版本。
     * {@code revisionId == null} 表示只读 legacy 行（{@code revision_id IS NULL}），不会与 body 行混用。
     */
    List<com.kama.jchatmind.retrieval.ScoredChunk> searchScoped(
            @Param("kbId") String kbId,
            @Param("revisionId") String revisionId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit);
}
