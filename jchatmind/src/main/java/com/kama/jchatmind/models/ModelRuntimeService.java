package com.kama.jchatmind.models;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** Builds real provider clients from catalog snapshots; there is no static-provider fallback. */
@Service
public class ModelRuntimeService {
    private static final Logger log = LoggerFactory.getLogger(ModelRuntimeService.class);
    private final ModelSecretCipher cipher;
    private final ModelSettings settings;
    private final WebClient.Builder webClientBuilder;
    private final ModelRequestPacer pacer;

    public ModelRuntimeService(ModelSecretCipher cipher, ModelSettings settings, WebClient.Builder webClientBuilder) {
        this(cipher, settings, webClientBuilder, new ModelRequestPacer(0));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ModelRuntimeService(ModelSecretCipher cipher, ModelSettings settings, WebClient.Builder webClientBuilder,
                               ModelRequestPacer pacer) {
        this.cipher = cipher;
        this.settings = settings;
        this.webClientBuilder = webClientBuilder;
        this.pacer = pacer;
    }

    public ChatClient chatClient(ModelDefinition model) {
        return createChatClient(model, settings.getRequestTimeoutSeconds(), null);
    }

    private ChatClient createChatClient(ModelDefinition model, int timeoutSeconds, Integer maxTokens) {
        if (model.kind() != ModelKind.LLM) throw new ModelException(400, "请选择语言模型");
        String apiKey = cipher.decrypt(model.id(), model.encryptedApiKey());
        if (!StringUtils.hasText(apiKey)) throw new ModelException(503, "语言模型尚未配置 API Key");
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)));
        requests.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        RestClient.Builder rest = RestClient.builder().requestFactory(requests);
        RetryTemplate retry = RetryTemplate.builder().maxAttempts(1).build();
        ChatModel provider;
        if ("deepseek".equals(model.protocol())) {
            DeepSeekApi api = DeepSeekApi.builder().baseUrl(model.baseUrl()).apiKey(apiKey)
                    .completionsPath("/chat/completions")
                    .restClientBuilder(rest).webClientBuilder(webClientBuilder.clone())
                    .responseErrorHandler(SAFE_ERRORS).build();
            DeepSeekChatOptions options = DeepSeekChatOptions.builder().model(model.model())
                    .maxTokens(maxTokens).build();
            provider = DeepSeekChatModel.builder().deepSeekApi(api).defaultOptions(options).retryTemplate(retry).build();
        } else if ("openai".equals(model.protocol()) || "zhipuai".equals(model.protocol())) {
            // ZhipuAI's v4 endpoint implements the same message/tool-call protocol.
            OpenAiApi api = OpenAiApi.builder().baseUrl(model.baseUrl()).apiKey(apiKey)
                    .completionsPath("/chat/completions").embeddingsPath("/embeddings")
                    .restClientBuilder(rest).webClientBuilder(webClientBuilder.clone())
                    .responseErrorHandler(SAFE_ERRORS).build();
            OpenAiChatOptions options = OpenAiChatOptions.builder().model(model.model())
                    .maxTokens(maxTokens).build();
            provider = OpenAiChatModel.builder().openAiApi(api).defaultOptions(options).retryTemplate(retry).build();
        } else {
            throw new ModelException(400, "不支持的语言模型协议");
        }
        return ChatClient.create(new SafeChatModel(provider, Duration.ofSeconds(timeoutSeconds), pacer));
    }

    public float[] embed(ModelDefinition model, String text) {
        return embed(model, text, Math.min(60, settings.getRequestTimeoutSeconds()));
    }

    private float[] embed(ModelDefinition model, String text, int timeoutSeconds) {
        if (model.kind() != ModelKind.EMBEDDING || !Integer.valueOf(1024).equals(model.dimensions())) {
            throw new ModelException(400, "当前知识库仅支持1024维向量模型");
        }
        if (!StringUtils.hasText(text)) throw new ModelException(400, "向量查询内容不能为空");
        boolean openai = "openai".equals(model.protocol());
        if (!openai && !"ollama".equals(model.protocol())) throw new ModelException(400, "不支持的向量模型协议");
        Map<String, String> body = openai ? Map.of("model", model.model(), "input", text)
                : Map.of("model", model.model(), "prompt", text);
        WebClient client = webClientBuilder.clone().baseUrl(model.baseUrl())
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(1024 * 1024)).build();
        WebClient.RequestBodySpec request = client.post().uri(openai ? "/embeddings" : "/api/embeddings");
        String apiKey = cipher.decrypt(model.id(), model.encryptedApiKey());
        if (StringUtils.hasText(apiKey)) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        try {
            JsonNode response = request.bodyValue(body).retrieve()
                    .onStatus(status -> status.isError(), result -> Mono.error(embeddingUnavailable()))
                    .bodyToMono(JsonNode.class).block(Duration.ofSeconds(timeoutSeconds));
            return extractEmbedding(response, openai);
        } catch (ModelException safe) {
            throw safe;
        } catch (Exception failure) {
            // Provider errors can contain echoed headers or URLs; neither are returned or logged.
            throw embeddingUnavailable();
        }
    }

    private float[] extractEmbedding(JsonNode response, boolean openai) {
        if (response == null) throw new ModelException(502, "向量服务返回了空响应");
        JsonNode values;
        if (openai) {
            JsonNode data = response.path("data");
            if (!data.isArray() || data.size() != 1) throw new ModelException(502, "向量服务返回的数据格式无效");
            values = data.path(0).path("embedding");
        } else {
            values = response.path("embedding");
        }
        if (!values.isArray() || values.size() != 1024) {
            throw new ModelException(502, "向量维度不匹配，当前知识库要求1024维");
        }
        float[] result = new float[1024];
        for (int i = 0; i < result.length; i++) {
            JsonNode number = values.get(i);
            if (!number.isNumber() || !Float.isFinite(number.floatValue())) {
                throw new ModelException(502, "向量服务返回了无效的数值");
            }
            result[i] = number.floatValue();
        }
        return result;
    }

    public ModelViews.TestResult test(ModelDefinition model) {
        long started = System.nanoTime();
        try {
            if (model.kind() == ModelKind.EMBEDDING) {
                float[] vector = embed(model, "连接测试", 20);
                return new ModelViews.TestResult(true, "向量模型连接正常", elapsed(started), vector.length);
            }
            String reply = createChatClient(model, 20, 32).prompt().user("Reply with OK.").call().content();
            if (!StringUtils.hasText(reply)) throw new ModelException(502, "语言模型返回了空内容");
            return new ModelViews.TestResult(true, "语言模型连接正常", elapsed(started), null);
        } catch (ModelException safe) {
            return new ModelViews.TestResult(false, safe.getMessage(), elapsed(started), null);
        } catch (Exception failure) {
            return new ModelViews.TestResult(false, "连接失败，请检查服务地址、模型名和凭据", elapsed(started), null);
        }
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private static ModelException embeddingUnavailable() { return new ModelException(502, "向量服务暂不可用，请联系管理员检查模型配置"); }
    private static ModelException llmUnavailable() { return new ModelException(502, "语言模型服务暂不可用，请联系管理员检查模型配置"); }

    private static final ResponseErrorHandler SAFE_ERRORS = new ResponseErrorHandler() {
        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException { return response.getStatusCode().isError(); }
        @Override
        public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
            // 只保留白名单诊断：状态码、Retry-After、供应商错误码、请求标识。
            // 响应头原文、响应体全文、URL 与凭据一律不写日志、不外传。
            ProviderFailure failure = ProviderFailure.of(response.getStatusCode().value(),
                    response.getHeaders(), readBriefBody(response));
            log.warn("Language model HTTP request failed: {}", failure.toDetail());
            throw new ProviderUnavailableException(failure);
        }
    };

    /** 错误体只读到固定长度，且只用于抽取稳定错误码，不原样记录。 */
    private static String readBriefBody(ClientHttpResponse response) {
        try {
            byte[] buffer = new byte[2048];
            int read = response.getBody().read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    private record SafeChatModel(ChatModel delegate, Duration timeout, ModelRequestPacer pacer) implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            pacer.await();
            try { return delegate.call(prompt); }
            catch (Exception failure) { throw rethrowWithDiagnostics(failure); }
        }

        @Override
        public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            pacer.await();
            return Flux.defer(() -> delegate.stream(prompt)).timeout(timeout)
                    .onErrorMap(ModelRuntimeService.SafeChatModel::rethrowWithDiagnostics);
        }


        /**
         * 记录根因并把白名单诊断带出去：有诊断就用 {@link ProviderUnavailableException}，
         * 没有（超时、解析错误等）才退化成固定的「暂不可用」。
         */
        private static RuntimeException rethrowWithDiagnostics(Throwable failure) {
            logFailure(failure);
            ProviderFailure diagnostics = ProviderFailure.find(failure);
            return diagnostics != null ? new ProviderUnavailableException(diagnostics) : llmUnavailable();
        }
        /**
         * 记录可分诊的失败根因：只留异常类名与去掉 URL／密钥后的短消息。
         *
         * <p>没有这一行时，{@link #llmUnavailable()} 会把超时、限流与解析错误压成同一句
         * 「服务暂不可用」，验收时无法区分失败来自供应商还是客户端。</p>
         */
        private static void logFailure(Throwable failure) {
            String message = failure.getMessage() == null ? "" : failure.getMessage();
            message = message.replaceAll("(?i)(https?://|bearer\\s+)[^\\s\"']+", "<redacted>")
                    .replaceAll("sk-[A-Za-z0-9_\\-]{8,}", "<redacted>")
                    .replaceAll("\\s+", " ");
            if (message.length() > 160) {
                message = message.substring(0, 160);
            }
            log.warn("Language model call failed: type={} message={}", failure.getClass().getSimpleName(), message);
        }
    }
}
