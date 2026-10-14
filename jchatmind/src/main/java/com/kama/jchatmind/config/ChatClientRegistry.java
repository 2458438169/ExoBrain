package com.kama.jchatmind.config;

import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.models.ModelRuntimeService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class ChatClientRegistry {

    private final ModelCatalogService catalog;
    private final ModelRuntimeService runtime;

    public ChatClientRegistry(ModelCatalogService catalog, ModelRuntimeService runtime) {
        this.catalog = catalog;
        this.runtime = runtime;
    }

    public ChatClient get(String key) {
        return runtime.chatClient(catalog.requireEnabledLlm(key));
    }
}
