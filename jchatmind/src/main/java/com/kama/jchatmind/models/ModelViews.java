package com.kama.jchatmind.models;

import java.time.Instant;

public final class ModelViews {
    private ModelViews() { }

    public record UserModel(String id, String name, ModelKind kind, String model,
                            boolean enabled, boolean defaultModel, Integer dimensions) {
        public static UserModel from(ModelDefinition value) {
            return new UserModel(value.id(), value.name(), value.kind(), value.model(),
                    value.enabled(), value.defaultModel(), value.dimensions());
        }
    }

    public record AdminModel(String id, String name, ModelKind kind, String protocol,
                             String baseUrl, String model, boolean enabled, boolean defaultModel,
                             Integer dimensions, boolean apiKeyConfigured,
                             Instant createdAt, Instant updatedAt) {
        public static AdminModel from(ModelDefinition value) {
            return new AdminModel(value.id(), value.name(), value.kind(), value.protocol(),
                    value.baseUrl(), value.model(), value.enabled(), value.defaultModel(),
                    value.dimensions(), value.encryptedApiKey() != null,
                    value.createdAt(), value.updatedAt());
        }
    }

    public record TestResult(boolean success, String message, long latencyMs, Integer dimensions) { }
}
