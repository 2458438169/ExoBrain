package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.models.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagServiceImplTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1023, 1536})
    void rejectsWrongDimensionsBeforeSimilarityQuery(int dimensions) {
        ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
        RagServiceImpl service = serviceReturning(dimensions, "ollama", mapper);

        ModelException error = assertThrows(ModelException.class,
                () -> service.similaritySearch("kb", "query"));

        assertTrue(error.getMessage().contains("1024"));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ollama", "openai"})
    void acceptsExpectedDimensionsFromBothSupportedResponseFormats(String protocol) {
        RagServiceImpl service = serviceReturning(1024, protocol, mock(ChunkBgeM3Mapper.class));

        assertEquals(1024, service.embed("query").length);
    }

    private RagServiceImpl serviceReturning(int dimensions, String protocol, ChunkBgeM3Mapper mapper) {
        String vector = "[" + String.join(",", Collections.nCopies(dimensions, "0.1")) + "]";
        String body = "openai".equals(protocol)
                ? "{\"data\":[{\"embedding\":" + vector + "}]}"
                : "{\"embedding\":" + vector + "}";
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build()));
        ModelDefinition model = new ModelDefinition("test-embedding", "Test", ModelKind.EMBEDDING,
                protocol, "http://localhost", "bge-m3", null, true, true, 1024, Instant.now(), Instant.now());
        ModelCatalogService catalog = mock(ModelCatalogService.class);
        when(catalog.defaultEmbedding()).thenReturn(model);
        when(catalog.embeddingForKnowledgeBase("kb")).thenReturn(model);
        ModelRuntimeService runtime = new ModelRuntimeService(new ModelSecretCipher(new ModelSettings()),
                new ModelSettings(), builder);
        return new RagServiceImpl(mapper, catalog, runtime);
    }
}
