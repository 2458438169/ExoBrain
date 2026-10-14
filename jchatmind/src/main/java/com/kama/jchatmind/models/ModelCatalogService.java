package com.kama.jchatmind.models;

import com.kama.jchatmind.config.EmbeddingProperties;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ModelCatalogService {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Set<String> LLM_PROTOCOLS = Set.of("deepseek", "zhipuai", "openai");
    private static final Set<String> EMBEDDING_PROTOCOLS = Set.of("ollama", "openai");
    private final ModelRepository repository;
    private final ModelSecretCipher cipher;

    public ModelCatalogService(ModelRepository repository, ModelSecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public List<ModelViews.UserModel> enabledModels() {
        return repository.findAll().stream().filter(ModelDefinition::enabled).map(ModelViews.UserModel::from).toList();
    }

    public List<ModelViews.AdminModel> adminModels() {
        return repository.findAll().stream().map(ModelViews.AdminModel::from).toList();
    }

    public ModelDefinition requireModel(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new ModelException(404, "模型不存在");
        return repository.find(id).orElseThrow(() -> new ModelException(404, "模型不存在"));
    }

    public ModelDefinition requireEnabledLlm(String id) {
        ModelDefinition model = requireModel(id);
        if (model.kind() != ModelKind.LLM || !model.enabled()) {
            throw new ModelException(400, "请选择已启用的语言模型");
        }
        return model;
    }

    /** Public validation entry point for Agent creation, editing and execution. */
    public void validateLlmSelection(String id) { requireEnabledLlm(id); }

    @Transactional
    public ModelViews.AdminModel create(ModelUpsertRequest request) {
        repository.lockCatalog();
        String id = required(request.id(), 64, "模型标识不能为空且长度不能超过64");
        if (!ID.matcher(id).matches()) throw new ModelException(400, "模型标识仅支持小写字母、数字、点、下划线和连字符");
        if (repository.find(id).isPresent()) throw new ModelException(409, "模型标识已存在");
        if (request.kind() == null) throw new ModelException(400, "请选择模型类型");
        if (Boolean.TRUE.equals(request.clearApiKey()) && StringUtils.hasText(request.apiKey())) {
            throw new ModelException(400, "不能同时设置和清除 API Key");
        }
        String apiKey = normalizeApiKey(request.apiKey());
        ModelDefinition model = new ModelDefinition(id, request.name(), request.kind(), request.protocol(),
                request.baseUrl(), request.model(), cipher.encrypt(id, apiKey),
                Boolean.TRUE.equals(request.enabled()), Boolean.TRUE.equals(request.defaultModel()),
                request.kind() == ModelKind.EMBEDDING ? Objects.requireNonNullElse(request.dimensions(), 1024) : request.dimensions(),
                Instant.now(), Instant.now());
        model = validate(model);
        if (model.defaultModel()) repository.clearDefault(model.kind());
        repository.insert(model);
        return ModelViews.AdminModel.from(requireModel(id));
    }

    @Transactional
    public ModelViews.AdminModel update(String id, ModelUpsertRequest request) {
        repository.lockCatalog();
        ModelDefinition previous = requireModel(id);
        if (request.id() != null && !request.id().equals(id)) throw new ModelException(400, "模型标识不能修改");
        if (request.kind() != null && request.kind() != previous.kind()) throw new ModelException(400, "模型类型不能修改");
        String encryptedKey = previous.encryptedApiKey();
        String apiKey = normalizeApiKey(request.apiKey());
        if (Boolean.TRUE.equals(request.clearApiKey())) {
            if (apiKey != null) throw new ModelException(400, "不能同时设置和清除 API Key");
            encryptedKey = null;
        } else if (apiKey != null) {
            encryptedKey = cipher.encrypt(id, apiKey);
        }
        boolean enabled = request.enabled() == null ? previous.enabled() : request.enabled();
        boolean defaultModel = request.defaultModel() == null ? previous.defaultModel() && enabled : request.defaultModel();
        ModelDefinition next = validate(new ModelDefinition(id, orPrevious(request.name(), previous.name()), previous.kind(),
                orPrevious(request.protocol(), previous.protocol()), orPrevious(request.baseUrl(), previous.baseUrl()),
                orPrevious(request.model(), previous.model()), encryptedKey, enabled, defaultModel,
                request.dimensions() == null ? previous.dimensions() : request.dimensions(),
                previous.createdAt(), Instant.now()));
        if (previous.kind() == ModelKind.EMBEDDING && repository.hasKnowledgeBaseBinding(id)
                && (!next.model().equals(previous.model()) || !next.protocol().equals(previous.protocol())
                || !Objects.equals(next.dimensions(), previous.dimensions()))) {
            throw new ModelException(409, "知识库已绑定此向量模型；请新建模型并设为默认，已有知识库继续使用原模型");
        }
        if (next.defaultModel()) repository.clearDefault(next.kind());
        repository.update(next);
        return ModelViews.AdminModel.from(requireModel(id));
    }

    @Transactional
    public void delete(String id) {
        repository.lockCatalog();
        ModelDefinition model = requireModel(id);
        if (repository.isReferenced(id)) throw new ModelException(409, "模型正在被 Agent 或知识库使用，不能删除");
        if (model.defaultModel()) throw new ModelException(409, "请先取消默认模型或设置其他默认模型");
        try {
            repository.delete(id);
        } catch (DataIntegrityViolationException referencedConcurrently) {
            throw new ModelException(409, "模型正在被 Agent 或知识库使用，不能删除");
        }
    }

    @Transactional
    public void bindNewKnowledgeBase(String kbId) {
        validateKnowledgeBaseId(kbId);
        repository.lockCatalog();
        if (repository.findKnowledgeBaseModel(kbId).isPresent()) return;
        ModelDefinition selected = repository.findDefaultEmbedding()
                .orElseThrow(() -> new ModelException(409, "尚未设置可用的默认向量模型，请联系管理员"));
        repository.bindKnowledgeBase(kbId, selected.id());
    }

    public ModelDefinition embeddingForKnowledgeBase(String kbId) {
        validateKnowledgeBaseId(kbId);
        ModelDefinition model = repository.findKnowledgeBaseModel(kbId)
                .orElseThrow(() -> new ModelException(409, "知识库尚未绑定向量模型，请联系管理员"));
        if (model.kind() != ModelKind.EMBEDDING || !model.enabled()) {
            throw new ModelException(409, "此知识库使用的向量模型已停用，请联系管理员");
        }
        return model;
    }

    public ModelDefinition defaultEmbedding() {
        return repository.findDefaultEmbedding()
                .orElseThrow(() -> new ModelException(409, "尚未设置可用的默认向量模型，请联系管理员"));
    }

    /** One-time import. Later boots cannot overwrite edits or revive deleted/disabled models. */
    @Transactional
    public void bootstrap(Environment environment, EmbeddingProperties embedding) {
        if (repository.lockCatalog()) return;
        String deepseekKey = usableBootstrapKey(environment.getProperty("spring.ai.deepseek.api-key", ""));
        insertIfMissing(new ModelDefinition("deepseek-chat", "DeepSeek Chat", ModelKind.LLM, "deepseek",
                environment.getProperty("spring.ai.deepseek.base-url", "https://api.deepseek.com"),
                environment.getProperty("spring.ai.deepseek.chat.options.model", "deepseek-chat"),
                cipher.encrypt("deepseek-chat", deepseekKey), deepseekKey != null, deepseekKey != null,
                null, Instant.now(), Instant.now()));

        // Preserve the existing optional identifier, but do not advertise an unverified provider as available.
        String zhipuKey = usableBootstrapKey(environment.getProperty("spring.ai.zhipuai.api-key", ""));
        String zhipuBase = environment.getProperty("spring.ai.zhipuai.base-url", "https://open.bigmodel.cn/api/paas");
        if (zhipuBase.endsWith("/api/paas")) zhipuBase += "/v4";
        insertIfMissing(new ModelDefinition("glm-4.6", "GLM 4.6", ModelKind.LLM, "zhipuai", zhipuBase,
                environment.getProperty("spring.ai.zhipuai.chat.options.model", "glm-4.6"),
                cipher.encrypt("glm-4.6", zhipuKey), false, false, null, Instant.now(), Instant.now()));

        insertIfMissing(new ModelDefinition("legacy-embedding", embedding.getModel(), ModelKind.EMBEDDING,
                embedding.getProtocol(), embedding.getBaseUrl(), embedding.getModel(),
                cipher.encrypt("legacy-embedding", usableBootstrapKey(embedding.getApiKey())),
                true, true, 1024, Instant.now(), Instant.now()));
        repository.bindExistingKnowledgeBases("legacy-embedding");
        repository.markBootstrapped();
    }

    private void insertIfMissing(ModelDefinition model) {
        if (repository.find(model.id()).isEmpty()) repository.insert(validate(model));
    }

    private ModelDefinition validate(ModelDefinition model) {
        String name = required(model.name(), 120, "模型名称不能为空且长度不能超过120");
        String providerModel = required(model.model(), 200, "服务端模型名不能为空且长度不能超过200");
        String protocol = required(model.protocol(), 16, "请选择模型协议").toLowerCase(Locale.ROOT);
        Set<String> protocols = model.kind() == ModelKind.LLM ? LLM_PROTOCOLS : EMBEDDING_PROTOCOLS;
        if (!protocols.contains(protocol)) throw new ModelException(400, "此模型类型不支持所选协议");
        String baseUrl = validBaseUrl(model.baseUrl());
        if (model.kind() == ModelKind.EMBEDDING && !Integer.valueOf(1024).equals(model.dimensions())) {
            throw new ModelException(400, "当前知识库仅支持1024维向量模型");
        }
        if (model.kind() == ModelKind.LLM && model.dimensions() != null) {
            throw new ModelException(400, "语言模型不需要设置向量维度");
        }
        if (model.enabled() && model.kind() == ModelKind.LLM && model.encryptedApiKey() == null) {
            throw new ModelException(400, "启用语言模型前请先配置 API Key");
        }
        if (model.defaultModel() && !model.enabled()) throw new ModelException(400, "默认模型必须处于启用状态");
        return new ModelDefinition(model.id(), name, model.kind(), protocol, baseUrl, providerModel,
                model.encryptedApiKey(), model.enabled(), model.defaultModel(), model.dimensions(),
                model.createdAt(), model.updatedAt());
    }

    private static String validBaseUrl(String input) {
        String value = required(input, 2048, "请输入有效的模型服务地址");
        try {
            URI uri = URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new ModelException(400, "服务地址须为 HTTP(S) 地址，不能包含账号密码、查询参数或片段");
        }
        return value.replaceAll("/+$", "");
    }

    private static String required(String value, int max, String message) {
        if (!StringUtils.hasText(value) || value.trim().length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw new ModelException(400, message);
        }
        return value.trim();
    }

    private static String normalizeApiKey(String key) {
        if (!StringUtils.hasText(key)) return null;
        if (key.length() > 4096 || key.chars().anyMatch(Character::isISOControl)) {
            throw new ModelException(400, "API Key 格式无效");
        }
        return key.trim();
    }

    private static String usableBootstrapKey(String value) {
        String key = normalizeApiKey(value);
        if (key == null) return null;
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith("your-") || lower.startsWith("placeholder") || lower.startsWith("dummy")
                || lower.equals("not-configured") || lower.equals("unused")) return null;
        return key;
    }

    private static String orPrevious(String value, String previous) { return value == null ? previous : value; }

    private static void validateKnowledgeBaseId(String id) {
        try {
            if (id == null || !UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new ModelException(404, "知识库不存在");
        }
    }
}
