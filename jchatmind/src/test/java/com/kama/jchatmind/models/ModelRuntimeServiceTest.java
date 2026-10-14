package com.kama.jchatmind.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelRuntimeServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "openai", "zhipuai"})
    void dynamicallyConfiguredProviderReallyExecutesToolCalls(String protocol) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        List<String> paths = new CopyOnWriteArrayList<>();
        List<String> authorization = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/custom/v1/chat/completions", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            paths.add(exchange.getRequestURI().getPath());
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int index = calls.incrementAndGet();
            String message = index == 1
                    ? "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-date\",\"type\":\"function\",\"function\":{\"name\":\"getDate\",\"arguments\":\"{}\"}}]}"
                    : "{\"role\":\"assistant\",\"content\":\"Tool executed.\"}";
            String response = "{\"id\":\"response-" + index + "\",\"object\":\"chat.completion\",\"created\":1,"
                    + "\"model\":\"custom-provider-model\",\"choices\":[{\"index\":0,\"message\":" + message
                    + ",\"finish_reason\":\"" + (index == 1 ? "tool_calls" : "stop") + "\"}],"
                    + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":5,\"total_tokens\":10}}";
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, data.length);
            try (var stream = exchange.getResponseBody()) { stream.write(data); }
        });
        server.start();
        try {
            ModelDefinition model = definition(protocol, ModelKind.LLM,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/custom/v1");
            ModelRuntimeService runtime = runtime(WebClient.builder(), 10);
            DateProbe date = new DateProbe();
            assertEquals("Tool executed.", runtime.chatClient(model).prompt().user("What is today's date?")
                    .tools(date).call().content());
            assertEquals(2, calls.get());
            assertEquals(1, date.calls.get());
            assertEquals("custom-provider-model", requests.get(0).path("model").asText());
            assertTrue(requests.get(0).path("tools").toString().contains("getDate"));
            assertTrue(requests.get(1).path("messages").toString().contains("2026-10-14"));
            assertEquals(List.of("/custom/v1/chat/completions", "/custom/v1/chat/completions"), paths);
            assertTrue(authorization.stream().allMatch(value -> value.equals("Bearer fixture-key")));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "ollama"})
    void embeddingUsesConfiguredEndpointModelAndKey(String protocol) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        List<String> authorization = new CopyOnWriteArrayList<>();
        String endpoint = "openai".equals(protocol) ? "/v1/embeddings" : "/v1/api/embeddings";
        server.createContext(endpoint, exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String vector = "[" + String.join(",", Collections.nCopies(1024, "0.25")) + "]";
            String response = "openai".equals(protocol)
                    ? "{\"data\":[{\"embedding\":" + vector + "}]}" : "{\"embedding\":" + vector + "}";
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, data.length);
            try (var stream = exchange.getResponseBody()) { stream.write(data); }
        });
        server.start();
        try {
            ModelDefinition model = definition(protocol, ModelKind.EMBEDDING,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            float[] vector = runtime(WebClient.builder(), 10).embed(model, "test input");
            assertEquals(1024, vector.length);
            assertEquals(0.25f, vector[0]);
            assertEquals("custom-provider-model", requests.get(0).path("model").asText());
            assertEquals("test input", requests.get(0).path("openai".equals(protocol) ? "input" : "prompt").asText());
            assertEquals("Bearer fixture-key", authorization.get(0));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"not-a-number\"", "null", "true", "1e1000"})
    void rejectsNonnumericAndNonfiniteVectors(String badValue) {
        List<String> values = new ArrayList<>(Collections.nCopies(1024, "0.1"));
        values.set(7, badValue);
        String response = "{\"embedding\":[" + String.join(",", values) + "]}";
        ModelRuntimeService runtime = runtime(returning(HttpStatus.OK, response), 5);
        assertEquals(502, assertThrows(ModelException.class,
                () -> runtime.embed(definition("ollama", ModelKind.EMBEDDING, "http://localhost"), "text")).getStatus());
    }

    @Test
    void errorsNeverEchoProviderBodiesOrCredentials() throws Exception {
        ModelRuntimeService runtime = runtime(returning(HttpStatus.UNAUTHORIZED, "fixture-key private upstream details"), 5);
        var result = runtime.test(definition("openai", ModelKind.EMBEDDING, "http://localhost/v1"));
        assertFalse(result.success());
        assertFalse(result.message().contains("fixture-key"));
        assertFalse(result.message().contains("upstream"));
        assertNull(result.dimensions());
    }

    @Test
    void nonresponsiveEmbeddingRequestHasABoundedTimeout() {
        ModelRuntimeService runtime = runtime(WebClient.builder().exchangeFunction(request -> Mono.never()), 5);
        assertTimeout(Duration.ofSeconds(8), () -> {
            ModelException failure = assertThrows(ModelException.class,
                    () -> runtime.embed(definition("ollama", ModelKind.EMBEDDING, "http://localhost"), "text"));
            assertEquals(502, failure.getStatus());
        });
    }

    private ModelRuntimeService runtime(WebClient.Builder builder, int seconds) {
        ModelSecretCipher cipher = mock(ModelSecretCipher.class);
        when(cipher.decrypt(anyString(), any())).thenReturn("fixture-key");
        ModelSettings settings = new ModelSettings();
        settings.setRequestTimeoutSeconds(seconds);
        return new ModelRuntimeService(cipher, settings, builder);
    }

    private WebClient.Builder returning(HttpStatus status, String body) {
        return WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build()));
    }

    private ModelDefinition definition(String protocol, ModelKind kind, String base) {
        return new ModelDefinition("custom", "Custom", kind, protocol, base, "custom-provider-model", "ciphertext",
                true, false, kind == ModelKind.EMBEDDING ? 1024 : null, Instant.now(), Instant.now());
    }

    public static class DateProbe {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(description = "Get the current date")
        public String getDate() { calls.incrementAndGet(); return "2026-10-14"; }
    }
}
