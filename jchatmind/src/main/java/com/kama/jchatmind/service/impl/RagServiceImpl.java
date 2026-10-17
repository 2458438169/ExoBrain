package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.models.ModelRuntimeService;
import com.kama.jchatmind.retrieval.RetrievalAuthorization;
import com.kama.jchatmind.retrieval.RetrievalHit;
import com.kama.jchatmind.retrieval.RetrievalService;
import com.kama.jchatmind.service.RagService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagServiceImpl implements RagService {
    private final ModelCatalogService catalog;
    private final ModelRuntimeService runtime;
    private final RetrievalService retrieval;

    public RagServiceImpl(ModelCatalogService catalog, ModelRuntimeService runtime, RetrievalService retrieval) {
        this.catalog = catalog;
        this.runtime = runtime;
        this.retrieval = retrieval;
    }

    @Override
    public float[] embed(String text) {
        return runtime.embed(catalog.defaultEmbedding(), text);
    }

    @Override
    public float[] embed(String kbId, String text) {
        return runtime.embed(catalog.embeddingForKnowledgeBase(kbId), text);
    }

    /**
     * {@inheritDoc}
     *
     * <p>过渡适配：走与结构化检索同一套作用域（KB + 当前活动索引版本 + 绑定模型），
     * 只是把命中压平成正文；不可用时抛异常，不会把服务失败伪装成“资料不足”。</p>
     */
    @Deprecated
    @Override
    public List<String> similaritySearch(String kbId, String title) {
        List<RetrievalHit> hits = retrieval.requireHits(kbId, title, RetrievalAuthorization.argumentBound(kbId));
        return hits.stream().map(RetrievalHit::content).toList();
    }
}