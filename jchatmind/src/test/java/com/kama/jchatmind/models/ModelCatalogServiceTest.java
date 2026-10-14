package com.kama.jchatmind.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.EmbeddingProperties;
import com.kama.jchatmind.config.ChatClientRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelCatalogServiceTest {
    private static final String KB = "22222222-2222-4222-8222-222222222222";
    private final ModelRepository repository = mock(ModelRepository.class);
    private final ModelSecretCipher cipher = mock(ModelSecretCipher.class);
    private final Map<String, ModelDefinition> records = new HashMap<>();
    private final ModelCatalogService catalog = new ModelCatalogService(repository, cipher);

    @BeforeEach
    void configureRepository() {
        when(repository.find(anyString())).thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(0))));
        when(repository.findAll()).thenAnswer(call -> records.values().stream().toList());
        doAnswer(call -> { ModelDefinition record = call.getArgument(0); records.put(record.id(), record); return null; })
                .when(repository).insert(any());
        doAnswer(call -> { ModelDefinition record = call.getArgument(0); records.put(record.id(), record); return null; })
                .when(repository).update(any());
        doAnswer(call -> { records.remove(call.getArgument(0)); return null; }).when(repository).delete(anyString());
        when(cipher.encrypt(anyString(), anyString())).thenReturn("encrypted-fixture");
    }

    @Test
    void onlyEnabledSafeFieldsAreVisibleToUsersAndNeitherViewReturnsAKey() throws Exception {
        records.put("live", llm("live", true, false));
        records.put("disabled", llm("disabled", false, false));
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var user = json.valueToTree(catalog.enabledModels());
        assertEquals(1, user.size());
        assertFalse(user.get(0).has("baseUrl"));
        assertFalse(user.get(0).has("protocol"));
        assertFalse(user.get(0).has("apiKey"));
        assertFalse(user.get(0).has("encryptedApiKey"));
        var admin = json.valueToTree(catalog.adminModels());
        assertEquals(2, admin.size());
        assertTrue(admin.get(0).path("apiKeyConfigured").asBoolean());
        assertFalse(admin.toString().contains("ciphertext"));
    }

    @Test
    void creationEncryptsTheKeyAndUsesTheRequestedDynamicIdentifier() {
        var result = catalog.create(request("custom-llm", ModelKind.LLM, "openai", "compatible-chat", "new-secret", true, false, null));
        assertEquals("custom-llm", result.id());
        assertTrue(result.apiKeyConfigured());
        assertEquals("encrypted-fixture", records.get("custom-llm").encryptedApiKey());
        verify(cipher).encrypt("custom-llm", "new-secret");
        catalog.validateLlmSelection("custom-llm");
    }

    @Test
    void blankKeyKeepsTheSecretAndClearingItRequiresDisablingTheLlm() {
        records.put("live", llm("live", true, true));
        catalog.update("live", patch(null, "", false, null, null, null));
        assertEquals("ciphertext", records.get("live").encryptedApiKey());
        verify(cipher, never()).encrypt(any(), any());
        assertEquals(400, assertThrows(ModelException.class,
                () -> catalog.update("live", patch(null, null, true, null, null, null))).getStatus());
        catalog.update("live", patch(null, null, true, false, null, null));
        assertNull(records.get("live").encryptedApiKey());
        assertFalse(records.get("live").defaultModel());
    }

    @Test
    void disabledLegacyIdentifierCannotFallBackToAStaticChatClient() {
        records.put("deepseek-chat", llm("deepseek-chat", false, false));
        ModelRuntimeService runtime = mock(ModelRuntimeService.class);
        ChatClientRegistry registry = new ChatClientRegistry(catalog, runtime);
        assertThrows(ModelException.class, () -> registry.get("deepseek-chat"));
        verifyNoInteractions(runtime);
    }

    @Test
    void boundEmbeddingIdentityIsImmutableButEndpointAndKeyCanBeRotated() {
        records.put("embedding", embedding("embedding", "original-model", true));
        when(repository.hasKnowledgeBaseBinding("embedding")).thenReturn(true);
        assertEquals(409, assertThrows(ModelException.class,
                () -> catalog.update("embedding", patch("replacement-model", null, false, null, null, null))).getStatus());
        assertEquals(400, assertThrows(ModelException.class,
                () -> catalog.update("embedding", patch(null, null, false, null, null, 1536))).getStatus());
        catalog.update("embedding", new ModelUpsertRequest(null, null, null, null,
                "https://replacement.example/v1", null, "rotated-key", false, null, null, null));
        assertEquals("https://replacement.example/v1", records.get("embedding").baseUrl());
        assertEquals("original-model", records.get("embedding").model());
    }

    @Test
    void newKnowledgeBasesUseTheCurrentDefaultAndExistingBindingsNeverChange() {
        ModelDefinition selected = embedding("new-embedding", "new-model", true);
        ModelDefinition previous = embedding("old-embedding", "old-model", true);
        when(repository.findDefaultEmbedding()).thenReturn(Optional.of(selected));
        when(repository.findKnowledgeBaseModel(KB)).thenReturn(Optional.empty());
        catalog.bindNewKnowledgeBase(KB);
        verify(repository).bindKnowledgeBase(KB, "new-embedding");
        clearInvocations(repository);
        when(repository.findKnowledgeBaseModel(KB)).thenReturn(Optional.of(previous));
        catalog.bindNewKnowledgeBase(KB);
        verify(repository, never()).bindKnowledgeBase(anyString(), anyString());
        assertSame(previous, catalog.embeddingForKnowledgeBase(KB));
    }

    @Test
    void disabledBindingFailsWithoutUsingTheNewDefault() {
        when(repository.findKnowledgeBaseModel(KB)).thenReturn(Optional.of(embedding("old", "old-model", false)));
        assertEquals(409, assertThrows(ModelException.class, () -> catalog.embeddingForKnowledgeBase(KB)).getStatus());
        verify(repository, never()).findDefaultEmbedding();
    }

    @Test
    void referencedModelsCannotBeDeletedAndUnusedOnesCan() {
        records.put("used", llm("used", false, false));
        records.put("unused", llm("unused", false, false));
        when(repository.isReferenced("used")).thenReturn(true);
        assertEquals(409, assertThrows(ModelException.class, () -> catalog.delete("used")).getStatus());
        verify(repository, never()).delete("used");
        catalog.delete("unused");
        assertFalse(records.containsKey("unused"));
    }

    @Test
    void invalidAddressNeverEchoesAnEmbeddedCredential() {
        ModelUpsertRequest request = new ModelUpsertRequest("bad", "Bad", ModelKind.LLM, "openai",
                "https://private:do-not-echo@example.test/v1", "model", null, false, false, false, null);
        ModelException error = assertThrows(ModelException.class, () -> catalog.create(request));
        assertEquals(400, error.getStatus());
        assertFalse(error.getMessage().contains("do-not-echo"));
        verify(repository, never()).insert(any());
    }

    @Test
    void bootstrapImportsExistingConfigurationOnlyOnceAndNeverEnablesUnverifiedZhipu() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.ai.deepseek.api-key", "seed-secret");
        EmbeddingProperties embedding = new EmbeddingProperties();
        catalog.bootstrap(env, embedding);
        assertTrue(records.get("deepseek-chat").enabled());
        assertFalse(records.get("glm-4.6").enabled());
        assertEquals("bge-m3", records.get("legacy-embedding").model());
        verify(repository).bindExistingKnowledgeBases("legacy-embedding");
        verify(repository).markBootstrapped();
        records.remove("deepseek-chat");
        when(repository.lockCatalog()).thenReturn(true);
        clearInvocations(repository);
        catalog.bootstrap(env, embedding);
        assertFalse(records.containsKey("deepseek-chat"));
        verify(repository, never()).insert(any());
        verify(repository, never()).bindExistingKnowledgeBases(anyString());
    }

    private ModelUpsertRequest request(String id, ModelKind kind, String protocol, String model,
                                       String key, Boolean enabled, Boolean defaultModel, Integer dimensions) {
        return new ModelUpsertRequest(id, "Test model", kind, protocol, "https://provider.example/v1",
                model, key, false, enabled, defaultModel, dimensions);
    }

    private ModelUpsertRequest patch(String model, String key, Boolean clear, Boolean enabled, Boolean defaultModel, Integer dimensions) {
        return new ModelUpsertRequest(null, null, null, null, null, model, key, clear, enabled, defaultModel, dimensions);
    }

    private ModelDefinition llm(String id, boolean enabled, boolean defaultModel) {
        return new ModelDefinition(id, id, ModelKind.LLM, "openai", "https://provider.example/v1",
                "provider-model", "ciphertext", enabled, defaultModel, null, Instant.now(), Instant.now());
    }

    private ModelDefinition embedding(String id, String model, boolean enabled) {
        return new ModelDefinition(id, id, ModelKind.EMBEDDING, "openai", "https://provider.example/v1",
                model, "ciphertext", enabled, enabled, 1024, Instant.now(), Instant.now());
    }
}
