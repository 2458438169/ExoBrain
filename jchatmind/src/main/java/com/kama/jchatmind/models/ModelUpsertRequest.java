package com.kama.jchatmind.models;

/** Null fields in a PATCH keep their existing values. Empty apiKey also keeps it. */
public record ModelUpsertRequest(String id, String name, ModelKind kind, String protocol,
                                 String baseUrl, String model, String apiKey,
                                 Boolean clearApiKey, Boolean enabled, Boolean defaultModel,
                                 Integer dimensions) {
    @Override
    public String toString() {
        return "ModelUpsertRequest[id=" + id + ", kind=" + kind + "]";
    }
}
