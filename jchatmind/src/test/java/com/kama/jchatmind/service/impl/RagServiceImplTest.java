package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.EmbeddingProperties;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RagServiceImplTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1023, 1536})
    void rejectsWrongDimensionsBeforeSimilarityQuery(int dimensions) {
        ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
        RagServiceImpl service = serviceReturning(dimensions, "ollama", mapper);

        IllegalStateException error = assertThrows(IllegalStateException.class,
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
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setProtocol(protocol);
        return new RagServiceImpl(builder, mapper, properties);
    }
}
