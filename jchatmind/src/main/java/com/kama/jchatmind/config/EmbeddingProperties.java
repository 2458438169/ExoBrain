package com.kama.jchatmind.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * embedding 服务配置。
 *
 * <p>支持两类端点，用 {@link #protocol} 区分：
 * <ul>
 *   <li>{@code ollama} —— Ollama 原生接口（{@code /api/embeddings}，请求体 {@code {"model","prompt"}}）</li>
 *   <li>{@code openai} —— OpenAI 兼容接口（{@code /embeddings}，请求体 {@code {"model","input"}}），
 *       任何兼容端点都能接，例如自建/第三方 embedding 服务</li>
 * </ul>
 *
 * <p>响应解析对两种形态都兼容（顶层 {@code embedding} 或 {@code data[0].embedding}），
 * 所以只有请求体与路径需要区分协议。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.embedding")
public class EmbeddingProperties {

    /** ollama | openai */
    private String protocol = "ollama";

    /** 服务地址。默认指向本机 Ollama */
    private String baseUrl = "http://localhost:11434";

    /**
     * 模型名。
     * ⚠️ 必须与建表 DDL 的 {@code chunk_bge_m3.embedding vector(1024)} 维度一致 ——
     * 维度不匹配不会在 Java 层报错，而是在 PG 插入时抛类型错。
     */
    private String model = "bge-m3";

    /** 留空则不发送 Authorization 头（本地 Ollama 不需要） */
    private String apiKey = "";

    public boolean isOpenAiCompatible() {
        return "openai".equalsIgnoreCase(protocol);
    }

    public String getPath() {
        return isOpenAiCompatible() ? "/embeddings" : "/api/embeddings";
    }
}
