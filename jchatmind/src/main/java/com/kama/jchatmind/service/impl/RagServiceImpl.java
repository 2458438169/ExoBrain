package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.models.ModelRuntimeService;
import com.kama.jchatmind.service.RagService;
import org.springframework.stereotype.Service;

import java.util.List;
@Service
public class RagServiceImpl implements RagService {
    private final ChunkBgeM3Mapper chunks;
    private final ModelCatalogService catalog;
    private final ModelRuntimeService runtime;

    public RagServiceImpl(ChunkBgeM3Mapper chunks, ModelCatalogService catalog, ModelRuntimeService runtime) {
        this.chunks = chunks;
        this.catalog = catalog;
        this.runtime = runtime;
    }

    @Override
    public float[] embed(String text) {
        return runtime.embed(catalog.defaultEmbedding(), text);
    }

    @Override
    public float[] embed(String kbId, String text) {
        return runtime.embed(catalog.embeddingForKnowledgeBase(kbId), text);
    }

    @Override
    public List<String> similaritySearch(String kbId, String title) {
        String queryEmbedding = toPgVector(embed(kbId, title));
        return chunks.similaritySearch(kbId, queryEmbedding, 3).stream().map(ChunkBgeM3::getContent).toList();
    }

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
