package com.kama.jchatmind.citation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.retrieval.RetrievalEnvelope;
import com.kama.jchatmind.retrieval.RetrievalHit;
import com.kama.jchatmind.retrieval.RetrievalStatus;
import com.kama.jchatmind.retrieval.SourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 一次运行的来源登记（run-local registry）。
 *
 * <p><b>唯一可信来源：</b>本次运行里真实执行完成的 {@code KnowledgeTool} 工具响应。登记入口只接受
 * 「工具调用 id + 实际返回数据」这样的配对，并要求该 id 属于本轮实际请求的工具调用；模型文本、
 * 用户消息、旧历史、客户端 metadata 与数据库里扫描到的字符串都不是来源，
 * {@link #registerCompletedCalls} 也从不读取它们。无法解析或 schemaVersion 不匹配的响应直接丢弃，
 * 不会伪造 hit。</p>
 *
 * <p><b>去重与标签：</b>标签由服务端分配，模型无法指定。候选按「同一来源范围只保留第一条」去重，
 * 再按 {@code (检索序号, rank)} 升序编号，因此同一批输入得到稳定编号。来源范围（{@link #sourceKey}）：
 * 有行号时用「KB + 文档 + 源哈希 + 起止行」，没有行号（legacy 索引）时用「KB + 文档 + chunkId」——
 * 两种情况都不会把两个不同章节合并。</p>
 */
public final class CitationRegistry {

    /** 只有这个工具的真实结果能登记来源。 */
    public static final String KNOWLEDGE_TOOL_NAME = "KnowledgeTool";

    /** 单条回答最多保留的引用数；超出部分记录 {@link NoticeReason#TOO_MANY_CITATIONS}。 */
    public static final int MAX_CITATIONS = 10;

    /** 引用节选的最大字符数（来自实际片段正文，不是模型文本）。 */
    public static final int EXCERPT_MAX_CHARACTERS = 300;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一次实际完成的工具调用：id 与名称来自 {@code ToolResponseMessage}，数据来自工具真实返回值。 */
    public record CompletedCall(String toolCallId, String toolName, String responseData) {
    }

    /** 一次已登记的真实检索命中（服务端字段的唯一样本，不含模型输入）。 */
    public record RegisteredCitation(
            String citationId,
            String toolCallId,
            int retrievalSequence,
            String retrievalId,
            String kbId,
            String chunkId,
            int rank,
            SourceLocation source,
            String indexVersion,
            String indexRevision,
            String content) {
    }

    /** 校验过程中被丢弃/被合并的原因，全部是安全固定码，不含内容与文档标识。 */
    public enum NoticeReason {
        /** 模型给出的 id 不属于本次运行已完成的检索（含编造、跨会话、跨用户、未执行工具）。 */
        UNKNOWN_CITATION_ID,
        /** id 属于本次运行，但来源已不在本次运行的获准范围内。 */
        SOURCE_NOT_AUTHORIZED,
        /** 引用的来源范围与已接受的引用相同，只保留第一条。 */
        DUPLICATE_SOURCE,
        /** 超出 {@link #MAX_CITATIONS}。 */
        TOO_MANY_CITATIONS,
        /** citationIds 参数形状无效（例如不是字符串数组）。 */
        MALFORMED_ARGUMENT
    }

    public record Notice(NoticeReason reason, int count) {
    }

    /** 校验结果；请求位置从 1 开始，映射只包含已授权且未超出上限的来源。 */
    public record Resolution(List<AnswerCitation> citations, List<Notice> notices,
                             Map<Integer, Integer> labelsByRequestIndex) {
        public Resolution {
            citations = List.copyOf(citations);
            notices = List.copyOf(notices);
            labelsByRequestIndex = Map.copyOf(labelsByRequestIndex);
        }
    }

    private final Map<String, RegisteredCitation> byCitationId = new LinkedHashMap<>();
    private int retrievalSequence;

    /**
     * 登记本轮真实完成的工具调用结果。
     *
     * @param completed            来自 {@code ToolResponseMessage} 的响应（id/名称/返回数据）
     * @param requestedToolCallIds 本轮 assistant 消息里实际请求的 toolCallId 集合；id 不在其中一律忽略
     * @return 实际登记的检索次数（只统计能解析并获得命中的 OK 信封）
     */
    public int registerCompletedCalls(List<CompletedCall> completed, Set<String> requestedToolCallIds) {
        Objects.requireNonNull(completed, "completed tool calls are required");
        Set<String> requested = requestedToolCallIds == null ? Set.of() : requestedToolCallIds;
        int registered = 0;
        for (CompletedCall call : completed) {
            if (call == null || call.toolCallId() == null || !requested.contains(call.toolCallId())) {
                continue;
            }
            if (!KNOWLEDGE_TOOL_NAME.equals(call.toolName())) {
                continue;
            }
            RetrievalEnvelope envelope = parseEnvelope(call.responseData());
            if (envelope == null || envelope.status() != RetrievalStatus.OK) {
                continue;
            }
            int sequence = ++retrievalSequence;
            for (RetrievalHit hit : envelope.hits()) {
                RegisteredCitation candidate = toRegistered(call.toolCallId(), sequence, envelope.retrievalId(), hit);
                if (candidate == null) {
                    continue;
                }
                // 同一个 citationId 只登记一次：重复返回同一命中不会改变已有身份。
                byCitationId.putIfAbsent(candidate.citationId(), candidate);
            }
            registered++;
        }
        return registered;
    }

    /** 本次运行已登记的真实命中（只读，供诊断与测试使用）。 */
    public List<RegisteredCitation> registered() {
        return List.copyOf(byCitationId.values());
    }

    public boolean isEmpty() {
        return byCitationId.isEmpty();
    }

    /**
     * 把模型选择的 citationId 校验并补全成引用。
     *
     * @param requestedCitationIds 模型给出的 id（形状无效时只记录 {@link NoticeReason#MALFORMED_ARGUMENT}）
     * @param isSourceAuthorized   服务端对来源 KB 的授权判断（在生成引用之前执行，不能为空）
     */
    public Resolution resolve(List<String> requestedCitationIds, Predicate<String> isSourceAuthorized) {
        Objects.requireNonNull(isSourceAuthorized, "source authorization is required");
        Map<NoticeReason, Integer> notices = new EnumMap<>(NoticeReason.class);

        List<RegisteredCitation> accepted = new ArrayList<>();
        if (requestedCitationIds != null) {
            Set<String> seenIds = new LinkedHashSet<>();
            for (String citationId : requestedCitationIds) {
                if (citationId == null || citationId.isBlank()) {
                    notice(notices, NoticeReason.MALFORMED_ARGUMENT);
                    continue;
                }
                if (!seenIds.add(citationId)) {
                    notice(notices, NoticeReason.DUPLICATE_SOURCE);
                    continue;
                }
                RegisteredCitation registered = byCitationId.get(citationId);
                if (registered == null) {
                    notice(notices, NoticeReason.UNKNOWN_CITATION_ID);
                    continue;
                }
                if (!isSourceAuthorized.test(registered.kbId())) {
                    notice(notices, NoticeReason.SOURCE_NOT_AUTHORIZED);
                    continue;
                }
                accepted.add(registered);
            }
        }

        accepted.sort(Comparator.comparingInt(RegisteredCitation::retrievalSequence)
                .thenComparingInt(RegisteredCitation::rank));

        Map<String, AnswerCitation> unique = new LinkedHashMap<>();
        Map<String, RegisteredCitation> authorizedIds = new LinkedHashMap<>();
        for (RegisteredCitation registered : accepted) {
            authorizedIds.put(registered.citationId(), registered);
            String key = sourceKey(registered);
            if (unique.containsKey(key)) {
                notice(notices, NoticeReason.DUPLICATE_SOURCE);
                continue;
            }
            if (unique.size() >= MAX_CITATIONS) {
                notice(notices, NoticeReason.TOO_MANY_CITATIONS);
                continue;
            }
            unique.put(key, new AnswerCitation(
                    unique.size() + 1,
                    registered.citationId(),
                    registered.retrievalId(),
                    registered.kbId(),
                    registered.chunkId(),
                    registered.source(),
                    registered.indexVersion(),
                    registered.indexRevision(),
                    excerpt(registered.content())));
        }

        Map<Integer, Integer> labels = new LinkedHashMap<>();
        if (requestedCitationIds != null) {
            for (int i = 0; i < requestedCitationIds.size(); i++) {
                RegisteredCitation registered = authorizedIds.get(requestedCitationIds.get(i));
                AnswerCitation citation = registered == null ? null : unique.get(sourceKey(registered));
                if (citation != null) labels.put(i + 1, citation.label());
            }
        }
        List<Notice> collected = notices.entrySet().stream()
                .map(entry -> new Notice(entry.getKey(), entry.getValue()))
                .toList();
        return new Resolution(new ArrayList<>(unique.values()), collected, labels);
    }

    /**
     * 去重键：同一 KB 内「同一文档的同一来源范围」只算一条来源。有行号时用源哈希与起止行，
     * 没有可靠位置（legacy 索引）时退化为 chunkId —— 两种情况都不会把两个不同章节合并。
     */
    static String sourceKey(RegisteredCitation citation) {
        SourceLocation source = citation.source();
        String range = source.startLine() != null && source.endLine() != null
                ? source.sourceHash() + "@" + source.startLine() + "-" + source.endLine()
                : "chunk:" + citation.chunkId();
        return citation.kbId() + "|" + source.documentId() + "|" + range;
    }

    private static RegisteredCitation toRegistered(String toolCallId, int sequence, String retrievalId, RetrievalHit hit) {
        if (hit == null || blank(hit.citationId()) || blank(hit.chunkId()) || blank(hit.kbId()) || hit.source() == null
                || blank(hit.source().documentId()) || hit.rank() < 1) {
            return null;
        }
        return new RegisteredCitation(
                hit.citationId(),
                toolCallId,
                sequence,
                retrievalId,
                hit.kbId(),
                hit.chunkId(),
                hit.rank(),
                hit.source(),
                blank(hit.indexVersion()) ? null : hit.indexVersion(),
                blank(hit.indexRevision()) ? null : hit.indexRevision(),
                hit.content());
    }

    /** 只接受共享契约 §2 的信封；结构不符（含 OK 却无 hits）由 record 自身拒绝，这里返回 null。 */
    private static RetrievalEnvelope parseEnvelope(String responseData) {
        if (blank(responseData)) {
            return null;
        }
        try {
            return JSON.readValue(responseData, RetrievalEnvelope.class);
        } catch (RuntimeException | JsonProcessingException invalid) {
            return null;
        }
    }

    /** 节选来自实际片段正文，压缩空白并限长；不改变字符内容，也不生成摘要。 */
    static String excerpt(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").strip();
        if (normalized.length() <= EXCERPT_MAX_CHARACTERS) {
            return normalized;
        }
        int end = EXCERPT_MAX_CHARACTERS - 1;
        if (Character.isHighSurrogate(normalized.charAt(end - 1))) {
            end--;
        }
        return normalized.substring(0, end).stripTrailing() + "…";
    }

    private static void notice(Map<NoticeReason, Integer> notices, NoticeReason reason) {
        notices.merge(reason, 1, Integer::sum);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
