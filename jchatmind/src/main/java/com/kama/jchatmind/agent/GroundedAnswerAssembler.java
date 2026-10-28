package com.kama.jchatmind.agent;

import com.kama.jchatmind.citation.CitationRegistry;
import com.kama.jchatmind.citation.AnswerCitation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 「陈述 + citationId + 原文短引」的装配器（新交付协议 grounded-v1）。
 *
 * <p>模型只交付陈述、它实际用到的来源 ID 与一段原文短引；**编号、排序、去重与来源卡片全部由服务端生成**，
 * 模型不再维护「[n] 对应第几项」的映射。规则：</p>
 * <ul>
 *   <li>citationId 必须是本轮真实登记过的检索命中，否则整轮判无效；</li>
 *   <li>原文短引必须出现在**该次命中自身的完整片段**里（空白归一后按包含判断），
 *       不能拿同文档别章的一句顶上；</li>
 *   <li>同一来源在多次陈述里复用同一个编号；编号由 {@link CitationRegistry#resolve} 按服务端顺序给出；</li>
 *   <li>资料不足走独立结果：只输出「本次可用资料不足以确认」加一句原因，不挂任何引用。</li>
 * </ul>
 *
 * <p>说明：短引存在只证明「引文确实来自该片段」，**不证明引文蕴含陈述**；语义支持仍由人工复核。</p>
 */
final class GroundedAnswerAssembler {

    static final String PROTOCOL_VERSION = "grounded-v1";
    static final String INSUFFICIENT_PREFIX = "本次可用资料不足以确认。";

    /** 模型交付的一条陈述：一句话 + 一个来源 ID + 一段原文短引。 */
    record Statement(String text, String citationId, String evidenceQuote) {
    }

    /** 装配结果：正文、来源卡片、校验通知、是否属于资料不足。 */
    record Assembled(String body, List<AnswerCitation> citations, List<CitationRegistry.Notice> notices,
                     boolean insufficient) {
    }

    private GroundedAnswerAssembler() {
    }

    static Assembled assemble(List<Statement> statements, String insufficient, CitationRegistry registry,
                              Predicate<String> isSourceAuthorized) {
        if (insufficient != null && !insufficient.isBlank()) {
            return new Assembled(INSUFFICIENT_PREFIX + " " + insufficient.strip(), List.of(), List.of(), true);
        }
        if (statements == null || statements.isEmpty()) {
            throw new InvalidAnswerFormatException("Model answer must contain at least one statement");
        }

        List<String> orderedIds = new ArrayList<>();
        for (Statement statement : statements) {
            if (statement == null || statement.text() == null || statement.text().isBlank()) {
                throw new InvalidAnswerFormatException("Model answer contains an empty statement");
            }
            CitationRegistry.RegisteredCitation registered = registered(registry, statement.citationId());
            if (registered == null) {
                throw new InvalidAnswerFormatException("Model answer references a source that could not be verified");
            }
            String quote = normalize(statement.evidenceQuote());
            if (quote.isEmpty() || !normalize(registered.content()).contains(quote)) {
                throw new InvalidAnswerFormatException("Model answer quotes text that is not in the retrieved source");
            }
            if (!orderedIds.contains(statement.citationId())) {
                orderedIds.add(statement.citationId());
            }
        }

        CitationRegistry.Resolution resolution = registry.resolve(orderedIds, isSourceAuthorized);
        if (resolution.citations().size() < orderedIds.size()) {
            // 有一个来源没通过授权校验：整轮判无效，避免只挂一部分来源造成错觉。
            throw new InvalidAnswerFormatException("Model answer references a source that could not be verified");
        }
        Map<String, Integer> numberByCitationId = new LinkedHashMap<>();
        resolution.labelsByRequestIndex().forEach((requestIndex, label) -> {
            if (requestIndex >= 1 && requestIndex <= orderedIds.size()) {
                numberByCitationId.put(orderedIds.get(requestIndex - 1), label);
            }
        });

        StringBuilder body = new StringBuilder();
        for (Statement statement : statements) {
            Integer number = numberByCitationId.get(statement.citationId());
            if (number == null) {
                throw new InvalidAnswerFormatException("Model answer references a source that could not be verified");
            }
            if (body.length() > 0) {
                body.append("\n\n");
            }
            body.append(statement.text().strip()).append(" [").append(number).append(']');
        }
        return new Assembled(body.toString(), resolution.citations(), resolution.notices(), false);
    }

    private static CitationRegistry.RegisteredCitation registered(CitationRegistry registry, String citationId) {
        if (citationId == null || citationId.isBlank()) {
            return null;
        }
        for (CitationRegistry.RegisteredCitation candidate : registry.registered()) {
            if (citationId.equals(candidate.citationId())) {
                return candidate;
            }
        }
        return null;
    }

    /** 空白归一：片段里有换行、短引常写成一行，所以比较前都去掉空白。 */
    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").strip();
    }
}