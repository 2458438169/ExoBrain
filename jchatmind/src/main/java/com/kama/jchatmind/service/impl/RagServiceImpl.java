package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.kama.jchatmind.config.EmbeddingProperties;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.RagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class RagServiceImpl implements RagService {

    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final EmbeddingProperties props;

    public RagServiceImpl(WebClient.Builder builder,
                          ChunkBgeM3Mapper chunkBgeM3Mapper,
                          EmbeddingProperties props) {
        this.props = props;
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.webClient = builder.baseUrl(props.getBaseUrl()).build();
        log.info("embedding 服务: protocol={} baseUrl={} model={}",
                props.getProtocol(), props.getBaseUrl(), props.getModel());
    }

    private float[] doEmbed(String text) {
        // 两种协议的请求体不同：Ollama 用 prompt，OpenAI 兼容用 input
        Map<String, String> body = props.isOpenAiCompatible()
                ? Map.of("model", props.getModel(), "input", text)
                : Map.of("model", props.getModel(), "prompt", text);

        WebClient.RequestBodySpec spec = webClient.post().uri(props.getPath());
        if (StringUtils.hasText(props.getApiKey())) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey());
        }

        JsonNode resp = spec.bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        Assert.notNull(resp, "Embedding 响应为空，请检查 embedding 服务是否可用: " + props.getBaseUrl());

        float[] embedding = extractEmbedding(resp);
        if (embedding.length == 0) {
            throw new IllegalStateException("Embedding 响应里没有向量, body = " + resp);
        }
        return embedding;
    }

    /**
     * 兼容两种响应形态：
     * <ul>
     *   <li>Ollama：顶层 {@code {"embedding":[...]}}</li>
     *   <li>OpenAI 兼容：{@code {"data":[{"embedding":[...]}]}}</li>
     * </ul>
     */
    private float[] extractEmbedding(JsonNode root) {
        JsonNode arr = root.path("embedding");
        if (!arr.isArray()) {
            arr = root.path("data").path(0).path("embedding");
        }
        if (!arr.isArray()) {
            return new float[0];
        }
        float[] v = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            v[i] = (float) arr.get(i).asDouble();
        }
        return v;
    }

    @Override
    public float[] embed(String text) {
        return doEmbed(text);
    }

    @Override
    public List<String> similaritySearch(String kbId, String title) {
        String queryEmbedding = toPgVector(doEmbed(title));
        List<ChunkBgeM3> chunks = chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, 3);
        return chunks.stream().map(ChunkBgeM3::getContent).toList();
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
