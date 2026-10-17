package com.kama.jchatmind.retrieval;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.models.ModelDefinition;
import com.kama.jchatmind.models.ModelRuntimeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 结构化检索实现：服务端限定 KB + 当前活动索引版本 + 模型绑定；授权发生在模型/向量查询之前。
 *
 * <p>状态区分：空索引或超过校准阈值 = {@code NO_MATCH}；服务/模型/索引异常 = {@code UNAVAILABLE}；
 * 两者的 hits 都为空，不会拿异常当“资料不足”。未授权是访问失败，不返回信封。</p>
 *
 * <p>SQL 在排序与 limit 之前限定 KB 与活动版本：body-v1 只读该 revision 的行，legacy 库只读
 * {@code revision_id IS NULL} 的旧行，两者不会混用。查询按 L2 距离排序（{@code metric = "l2"}），
 * 距离是有限非负值，不是概率，也不做 {@code 1 - distance} 的“置信度”换算。</p>
 */
@Service
@Slf4j
public class RetrievalServiceImpl implements RetrievalService {

    /** 查询文本上限，避免把无界输入送进向量服务。 */
    static final int MAX_QUERY_CHARACTERS = 4000;

    private final ChunkBgeM3Mapper chunks;
    private final ModelCatalogService catalog;
    private final ModelRuntimeService runtime;
    private final RetrievalIndexService indexService;
    private final RetrievalIndexRepository repository;
    private final RetrievalSettings settings;

    public RetrievalServiceImpl(ChunkBgeM3Mapper chunks, ModelCatalogService catalog, ModelRuntimeService runtime,
                                RetrievalIndexService indexService, RetrievalIndexRepository repository,
                                RetrievalSettings settings) {
        this.chunks = chunks;
        this.catalog = catalog;
        this.runtime = runtime;
        this.indexService = indexService;
        this.repository = repository;
        this.settings = settings;
    }

    @Override
    public RetrievalEnvelope retrieve(String kbId, String query, Predicate<String> maySearchKb) {
        String retrievalId = UUID.randomUUID().toString();
        QueryOutcome outcome = query(kbId, query, maySearchKb);
        return switch (outcome.status()) {
            case OK -> RetrievalEnvelope.ok(retrievalId, hits(retrievalId, outcome));
            case NO_MATCH -> RetrievalEnvelope.noMatch(retrievalId, outcome.errorCode());
            case UNAVAILABLE -> RetrievalEnvelope.unavailable(retrievalId, outcome.errorCode());
        };
    }

    @Override
    public List<RetrievalHit> requireHits(String kbId, String query, Predicate<String> maySearchKb) {
        QueryOutcome outcome = query(kbId, query, maySearchKb);
        if (outcome.status() == RetrievalStatus.OK) {
            return hits(UUID.randomUUID().toString(), outcome);
        }
        if (outcome.cause() != null) {
            throw outcome.cause();
        }
        throw new IndexingException("检索不可用: " + outcome.errorCode());
    }

    private QueryOutcome query(String kbId, String query, Predicate<String> maySearchKb) {
        // 1) 授权：任何模型绑定解析、向量调用与向量查询之前完成；未授权不产生结果。
        if (maySearchKb == null || !maySearchKb.test(kbId)) {
            throw new RetrievalAccessDeniedException();
        }
        // 2) 参数校验：非法输入不是一次成功检索。
        String canonicalKb = validKbId(kbId);
        if (canonicalKb == null || query == null || query.isBlank() || query.length() > MAX_QUERY_CHARACTERS) {
            return QueryOutcome.unavailable(RetrievalErrorCode.INVALID_REQUEST, null);
        }
        // 3) 模型绑定（KB 级）——失败即 UNAVAILABLE，不回退到默认模型，也不换别的向量空间。
        ModelDefinition model;
        try {
            model = catalog.embeddingForKnowledgeBase(canonicalKb);
        } catch (RuntimeException unbound) {
            return QueryOutcome.unavailable(RetrievalErrorCode.MODEL_BINDING_UNAVAILABLE, unbound);
        }
        // 4) 当前活动索引版本（legacy 或某次已发布 revision）。
        IndexScope scope;
        try {
            scope = indexService.resolveScope(canonicalKb);
        } catch (RuntimeException unreadable) {
            return QueryOutcome.unavailable(RetrievalErrorCode.INDEX_UNAVAILABLE, unreadable);
        }
        // 5) 查询向量：始终使用该 KB 绑定的模型；维度与有限性由运行时校验。
        float[] vector;
        try {
            vector = runtime.embed(model, query.strip());
        } catch (RuntimeException unavailable) {
            return QueryOutcome.unavailable(RetrievalErrorCode.EMBEDDING_UNAVAILABLE, unavailable);
        }
        // 6) 向量查询：KB + 活动版本限定在 SQL 里，排序/limit 之后才回到 Java。
        List<ScoredChunk> rows;
        try {
            rows = chunks.searchScoped(canonicalKb, scope.revisionId(), toPgVector(vector), settings.getTopK());
        } catch (RuntimeException unreadable) {
            return QueryOutcome.unavailable(RetrievalErrorCode.INDEX_UNAVAILABLE, unreadable);
        }
        if (rows == null) {
            rows = List.of();
        }
        for (ScoredChunk row : rows) {
            Double distance = row.getDistance();
            if (distance == null || !Double.isFinite(distance) || distance < 0) {
                // 距离不合法说明索引数据损坏：不猜一个数字，也不降级成“资料不足”。
                return QueryOutcome.unavailable(RetrievalErrorCode.INDEX_INVALID, null);
            }
        }
        if (rows.isEmpty()) {
            // legacy 库有旧行、已发布版本至少有 1 个 chunk：查询没命中就是 NO_MATCH；
            // 只有“这个 KB 还没有任何已发布正文索引”才是 NO_INDEXED_CONTENT。
            return QueryOutcome.noMatch(scope.hasPublishedRevision() || scope.legacyScope()
                    ? RetrievalErrorCode.NO_MATCH : RetrievalErrorCode.NO_INDEXED_CONTENT);
        }
        List<ScoredChunk> ranked = new ArrayList<>(rows);
        Double maxDistance = settings.getMaxDistance();
        if (maxDistance != null && ranked.get(0).getDistance() > maxDistance) {
            // 已校准的拒绝阈值：宁可返回 NO_MATCH，也不返回一段无关资料。
            return QueryOutcome.noMatch(RetrievalErrorCode.NO_MATCH);
        }
        return new QueryOutcome(RetrievalStatus.OK, null, scope, canonicalKb, ranked, null);
    }

    private List<RetrievalHit> hits(String retrievalId, QueryOutcome outcome) {
        Set<String> documentIds = new LinkedHashSet<>();
        for (ScoredChunk row : outcome.rows()) {
            documentIds.add(row.getDocId().toLowerCase(Locale.ROOT));
        }
        Map<String, String> names;
        try {
            names = repository.documentNames(documentIds);
        } catch (RuntimeException unreadable) {
            names = Map.of();
        }
        List<RetrievalHit> hits = new ArrayList<>();
        for (ScoredChunk row : outcome.rows()) {
            ChunkMetadata metadata = ChunkMetadata.decode(row.getMetadata());
            String documentId = row.getDocId().toLowerCase(Locale.ROOT);
            String documentName = names.getOrDefault(documentId, metadata.documentName());
            SourceLocation source = new SourceLocation(documentId, documentName == null ? "" : documentName,
                    metadata.headingPath(),
                    row.getSourceHash() != null ? row.getSourceHash() : metadata.sourceHash(),
                    row.getStartLine() != null ? row.getStartLine() : metadata.startLine(),
                    row.getEndLine() != null ? row.getEndLine() : metadata.endLine());
            hits.add(new RetrievalHit(retrievalId + ":" + (hits.size() + 1), row.getId(), outcome.kbId(),
                    hits.size() + 1, row.getContent() == null ? "" : row.getContent(), source,
                    row.getIndexVersion() == null ? outcome.scope().indexVersion().wire() : row.getIndexVersion(),
                    outcome.scope().indexRevision(), RetrievalHit.METRIC_L2, row.getDistance()));
        }
        return hits;
    }

    private static String validKbId(String kbId) {
        try {
            return kbId != null && UUID.fromString(kbId).toString().equalsIgnoreCase(kbId)
                    ? UUID.fromString(kbId).toString() : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static String toPgVector(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector[i]);
        }
        return literal.append(']').toString();
    }

    /** 内部结果：状态 + 安全错误码 + 原始异常（给过渡适配重新抛出，不对外暴露）。 */
    private record QueryOutcome(RetrievalStatus status, String errorCode, IndexScope scope, String kbId,
                                List<ScoredChunk> rows, RuntimeException cause) {

        static QueryOutcome noMatch(String errorCode) {
            return new QueryOutcome(RetrievalStatus.NO_MATCH, errorCode, null, null, List.of(), null);
        }

        static QueryOutcome unavailable(String errorCode, RuntimeException cause) {
            return new QueryOutcome(RetrievalStatus.UNAVAILABLE, errorCode, null, null, List.of(), cause);
        }
    }
}