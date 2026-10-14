package com.kama.jchatmind.models;

import com.kama.jchatmind.config.EmbeddingProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ModelCatalogBootstrap implements ApplicationRunner {
    private final ModelCatalogService catalog;
    private final ModelSecretCipher cipher;
    private final Environment environment;
    private final EmbeddingProperties embedding;

    public ModelCatalogBootstrap(ModelCatalogService catalog, ModelSecretCipher cipher,
                                 Environment environment, EmbeddingProperties embedding) {
        this.catalog = catalog;
        this.cipher = cipher;
        this.environment = environment;
        this.embedding = embedding;
    }

    @Override
    public void run(ApplicationArguments args) {
        // A development context can start before private model configuration exists.
        // Production mounts this required key file; APIs never fall back to static providers.
        if (cipher.isConfigured()) catalog.bootstrap(environment, embedding);
    }
}
