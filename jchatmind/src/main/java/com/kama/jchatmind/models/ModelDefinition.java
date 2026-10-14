package com.kama.jchatmind.models;

import java.time.Instant;

/** Internal persistence object; controllers must only return ModelViews. */
public record ModelDefinition(String id, String name, ModelKind kind, String protocol,
                              String baseUrl, String model, String encryptedApiKey,
                              boolean enabled, boolean defaultModel, Integer dimensions,
                              Instant createdAt, Instant updatedAt) {
    @Override
    public String toString() {
        return "ModelDefinition[id=" + id + ", kind=" + kind + ", enabled=" + enabled + "]";
    }
}
