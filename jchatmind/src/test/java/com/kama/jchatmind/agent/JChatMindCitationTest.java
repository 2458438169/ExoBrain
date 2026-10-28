package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.DirectAnswerTool;
import com.kama.jchatmind.citation.AnswerCitation;
import com.kama.jchatmind.agent.tools.GroundedAnswerTool;
import com.kama.jchatmind.citation.CitationRegistry;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.execution.RunLifecycleService;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.retrieval.RetrievalEnvelope;
import com.kama.jchatmind.retrieval.RetrievalHit;
import com.kama.jchatmind.retrieval.SourceLocation;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行时循环里的引用行为：模型只能选择 citationId，服务端按本次运行真实完成的检索结果补全来源。
 *
 * <p>模型调用由 {@link ScriptedRuntime} 回放（不联网、不使用密钥），工具执行走真实的 Spring AI
 * {@code ToolCallingManager} + {@code MethodToolCallback}，因此执行循环、工具响应与消息落盘前的 DTO
 * 都是真实路径。</p>
 */
class JChatMindCitationTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String KB = "44444444-4444-4444-8444-444444444444";
    private static final String OTHER_KB = "55555555-5555-4555-8555-555555555555";
    private static final String DOC = "66666666-6666-4666-8666-666666666666";

    @Test
    @DisplayName("citations are completed from this run's real retrieval, not from the model text")
    void citationsComeFromTheRealRetrievalOfThisRun() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "第一篇片段的正文", 1, 3, 5),
                hit("ret-1:2", "chunk-b", "第二篇片段的正文", 2, 8, 9))));

        List<ChatMessageDTO> saved = new ArrayList<>();
        run(List.of(
                knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "第二篇事实 [1]；第一篇事实 [2]。", "[\"ret-1:2\",\"ret-1:1\"]")),
                saved, List.of(kb(KB)), envelope);

        ChatMessageDTO answer = answerIn(saved);
        List<AnswerCitation> citations = answer.getMetadata().getCitations();
        assertNotNull(citations);
        assertEquals(2, citations.size());
        // 编号由服务端按 (检索序号, rank) 生成：模型给出的顺序（先 2 后 1）不影响编号。
        assertEquals(List.of(1, 2), citations.stream().map(AnswerCitation::label).toList());
        assertEquals(List.of("chunk-a", "chunk-b"), citations.stream().map(AnswerCitation::chunkId).toList());
        assertEquals("第二篇事实 [2]；第一篇事实 [1]。", answer.getContent(),
                "prose markers must follow the same permutation as the verified source labels");

        AnswerCitation first = citations.get(0);
        assertEquals("ret-1:1", first.citationId());
        assertEquals("ret-1", first.retrievalId());
        assertEquals(KB, first.kbId());
        assertEquals("body-v1", first.indexVersion());
        assertEquals("rev-1", first.indexRevision());
        assertEquals(DOC, first.source().documentId());
        assertEquals("《合成资料》", first.source().documentName());
        assertEquals(List.of("章节"), first.source().headingPath());
        assertEquals("hash-chunk-a", first.source().sourceHash());
        assertEquals(3, first.source().startLine());
        assertEquals(5, first.source().endLine());
        assertEquals("第一篇片段的正文", first.excerpt());
    }

    @Test
    @DisplayName("fabricated, foreign and never-executed ids produce no citation at all")
    void fabricatedOrForeignIdsProduceNoCitations() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(hit("ret-1:1", "chunk-a", "片段", 1, 1, 2))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        run(List.of(
                knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "回答正文",
                        "[\"made-up:1\",\"other-session-retrieval:1\",\"ret-1:99\",\"99999999-9999-4999-8999-999999999999\"]")),
                saved, List.of(kb(KB)), envelope);

        assertNull(answerIn(saved).getMetadata().getCitations(),
                "no citation may be fabricated for unknown ids");
    }

    @Test
    void duplicateIdsAndSourceAliasesUseTheSameVerifiedLabel() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "同一片段", 1, 3, 5),
                hit("ret-1:2", "chunk-a", "同一片段", 2, 3, 5))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        run(List.of(knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "事实 [1]；另一提法 [2]；重复来源 [3]。",
                        "[\"ret-1:2\",\"ret-1:1\",\"ret-1:2\"]")), saved, List.of(kb(KB)), envelope);
        assertEquals(1, answerIn(saved).getMetadata().getCitations().size());
        assertEquals("事实 [1]；另一提法 [1]；重复来源 [1]。", answerIn(saved).getContent());
    }

    @Test
    void aReferencedUnknownIdCannotBorrowTheNextValidSourcesLabel() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "真实片段", 1, 3, 5))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(List.of(
                knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "未知来源的说法 [1]；真实来源 [2]。",
                        "[\"other-run:1\",\"ret-1:1\"]")), saved, List.of(kb(KB)), envelope));
        assertEquals("INVALID_ANSWER_FORMAT", RunLifecycleService.classify(error));
        assertFalse(saved.stream().anyMatch(m -> m.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                && !m.getContent().isEmpty()), "an invalid reference must stop before answer persistence");
    }

    @Test
    void anUnreferencedRejectedIdDoesNotShiftTheMeaningOfAValidMarker() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "真实片段", 1, 3, 5))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        run(List.of(knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "真实片段支持这句话 [2]。", "[\"unknown:1\",\"ret-1:1\"]")),
                saved, List.of(kb(KB)), envelope);
        assertEquals("真实片段支持这句话 [1]。", answerIn(saved).getContent());
        assertEquals("chunk-a", answerIn(saved).getMetadata().getCitations().getFirst().chunkId());
    }

    @Test
    void aReferencedUnauthorizedSourceNeverProducesAnAnswer() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "越权来源", 1, 3, 5))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        assertThrows(RuntimeException.class, () -> run(List.of(knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "说法 [1]", "[\"ret-1:1\"]")), saved, List.of(kb(OTHER_KB)), envelope));
        assertFalse(saved.stream().anyMatch(m -> m.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                && !m.getContent().isEmpty()));
    }

    @Test
    void unsupportedOrOutOfRangePositionsAreRejectedBeforePersistence() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "片段", 1, 3, 5))));
        for (String marker : List.of("[2]", "[0]", "[1-3]", "[9999999999999999999999999]")) {
            List<ChatMessageDTO> saved = new ArrayList<>();
            RuntimeException error = assertThrows(RuntimeException.class, () -> run(List.of(
                    knowledgeRound("call-k1", envelope), answerRound("call-a1", "说法 " + marker,
                            "[\"ret-1:1\"]")), saved, List.of(kb(KB)), envelope));
            assertEquals("INVALID_ANSWER_FORMAT", RunLifecycleService.classify(error));
            assertFalse(saved.stream().anyMatch(m -> m.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                    && !m.getContent().isEmpty()));
        }
    }

    @Test
    void renumberingPreservesCodeLinksAndEscapedExamplesWithTheirOriginalOffsets() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "第一篇", 1, 3, 5),
                hit("ret-1:2", "chunk-b", "第二篇", 2, 8, 9))));
        String examples = "代码 `array[1]` 和 ``[2]``；转义 \\[1]；链接 [[1]](https://example.test/[2])。\r\n"
                + "\r\n```java\r\narray[1]; // [99]\r\n```\r\n"
                + "\r\n~~~text\r\n[2]\r\n~~~\r\n"
                + "\r\n    array[1]; // indented code [99]\r\n\r\n"
                + "[示例 [1]][example]\r\n\r\n[example]: https://example.test/[2]\r\n\r\n";
        List<ChatMessageDTO> saved = new ArrayList<>();
        run(List.of(knowledgeRound("call-k1", envelope),
                answerRound("call-a1", examples + "第二篇 [1]；第一篇 [2]；合并 [1, 2]。",
                        "[\"ret-1:2\",\"ret-1:1\"]")), saved, List.of(kb(KB)), envelope);
        assertEquals(examples + "第二篇 [2]；第一篇 [1]；合并 [2][1]。", answerIn(saved).getContent());
    }

    @Test
    @DisplayName("an answer without citationIds still works (compatibility with the old directAnswer call)")
    void answerWithoutCitationIdsStillWorks() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(hit("ret-1:1", "chunk-a", "片段", 1, 1, 2))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        // 正文里的数组下标 array[1] 不是引用标记，照旧原样保留（引用标记的判定见下一条用例）。
        run(List.of(
                knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "普通答复，数组元素 array[1]，选项编号 2。", null)),
                saved, List.of(kb(KB)), envelope);

        assertNull(answerIn(saved).getMetadata().getCitations());
        assertEquals("普通答复，数组元素 array[1]，选项编号 2。", answerIn(saved).getContent());
        assertTrue(saved.stream().anyMatch(message -> message.getRole() == ChatMessageDTO.RoleType.TOOL
                        && message.getContent() != null && message.getContent().contains("答复已交付")),
                "the directAnswer tool still completes a plain answer");
    }
    @Test
    @DisplayName("an answer whose citation markers have no verified source is rejected before persistence")
    void citationMarkersWithoutSourcesNeverPersistAnAnswer() throws Exception {
        List<ChatMessageDTO> saved = new ArrayList<>();
        // 模型在自己的正文里写了 [1][2]，但没有给出任何 citationIds：这两个编号没有任何可核验对象。
        ChatResponse fabricated = answerRound("call-a1", "结论一 [1]，要点二 [2]。", null);
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> run(List.of(fabricated), saved, List.of(kb(KB)), ""));
        assertEquals("INVALID_ANSWER_FORMAT", RunLifecycleService.classify(error));
        assertFalse(saved.stream().anyMatch(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT),
                "an answer with unverifiable citation numbers must not persist");
    }

    @Test
    @DisplayName("a glued Chinese marker or an out-of-range long marker is rejected before persistence too")
    void gluedAndLongMarkersWithoutSourcesNeverPersistAnAnswer() throws Exception {
        for (String answer : List.of("结论[1]。", "结论 [1][2]。", "结论 [1000]。", "结论 [12345678901]。",
                "结论[1][missing]。", "结论 <[1]>。", "结论 [1, 2]。", "结论 [1， 2]。")) {
            List<ChatMessageDTO> saved = new ArrayList<>();
            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> run(List.of(answerRound("call-a1", answer, null)), saved, List.of(kb(KB)), ""),
                    "answer must be rejected: " + answer);
            assertEquals("INVALID_ANSWER_FORMAT", RunLifecycleService.classify(error), answer);
            assertFalse(saved.stream().anyMatch(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT),
                    "no answer body may persist for: " + answer);
        }
    }


    @Test
    @DisplayName("a source outside the run's own knowledge bases is rejected even though it was really retrieved")
    void sourceOutsideTheRunsKnowledgeBasesIsRejected() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(hit("ret-1:1", "chunk-a", "片段", 1, 1, 2))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        // 本次运行允许的 KB 清单里没有命中来源的 KB（来源已不在获准范围）。
        run(List.of(
                knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "回答正文", "[\"ret-1:1\"]")),
                saved, List.of(kb(OTHER_KB)), envelope);

        assertNull(answerIn(saved).getMetadata().getCitations());
    }

    @Test
    @DisplayName("a source that was never retrieved in this run cannot be registered")
    void unrequestedRetrievalCannotRegisterASource() throws Exception {
        List<ChatMessageDTO> saved = new ArrayList<>();
        ScriptedRuntime runtime = run(List.of(answerRound("call-a1", "回答正文", "[\"ret-1:1\"]")),
                saved, List.of(kb(KB)), envelope(RetrievalEnvelope.ok("ret-1",
                        List.of(hit("ret-1:1", "chunk-a", "片段", 1, 1, 2)))));

        assertNull(answerIn(saved).getMetadata().getCitations());
        assertFalse(runtime.registeredCitations(),
                "a run without a completed retrieval may not register any source");
    }

    @Test
    void toolParameterSpillNeverPersistsAnAnswer() throws Exception {
        List<ChatMessageDTO> saved = new ArrayList<>();
        ChatResponse malformed = answerRound("call-a1",
                "取消后迟到答复不会写入。</answer>\n<parameter name=\"citationIds\">[\"ret-1:1\"]", null);
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> run(List.of(malformed), saved, List.of(), ""));
        assertEquals("INVALID_ANSWER_FORMAT", RunLifecycleService.classify(error));
        assertFalse(saved.stream().anyMatch(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT),
                "the malformed answer must be rejected before persistence");
    }

    @Test
    void fallbackContentUsesTheSameFormatGuard() {
        List<ChatMessageDTO> saved = new ArrayList<>();
        ChatResponse malformed = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("回答</answer>\n<parameter name=\"citationIds\">[]").build())));
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> run(List.of(malformed), saved, List.of(), ""));
        assertEquals("INVALID_ANSWER_FORMAT", RunLifecycleService.classify(error));
        assertTrue(saved.isEmpty());
    }

    @Test
    void xmlExamplesInFencedCodeRemainUnmodified() throws Exception {
        for (String fence : List.of("```", "~~~~")) {
            List<ChatMessageDTO> saved = new ArrayList<>();
            String answer = "XML 示例：\n" + fence + "xml\n</answer>\n<parameter name=\"citationIds\">[]\n"
                    + fence + fence.charAt(0) + "\n这是代码中的示例。";
            run(List.of(answerRound("call-a1", answer, null)), saved, List.of(), "");
            assertEquals(answer, answerIn(saved).getContent());
        }
    }

    @Test
    void inlineProtocolExampleRemainsUnmodified() throws Exception {
        List<ChatMessageDTO> saved = new ArrayList<>();
        String answer = "异常边界的例子是 `</answer><parameter name=\"citationIds\">[]`。";
        run(List.of(answerRound("call-a1", answer, null)), saved, List.of(), "");
        assertEquals(answer, answerIn(saved).getContent());
    }

    @Test
    void aCodeExampleDoesNotHideALaterSpill() throws Exception {
        List<ChatMessageDTO> saved = new ArrayList<>();
        ChatResponse malformed = answerRound("call-a1",
                "```xml\n<answer>示例</answer>\n```\n回答</answer><parameter name=\"citationIds\">[]", null);
        assertThrows(RuntimeException.class, () -> run(List.of(malformed), saved, List.of(), ""));
        assertTrue(saved.isEmpty());
    }
    // ------------------------------------------------------------ grounded-v1 交付协议

    @Test
    @DisplayName("grounded：陈述绑定来源，编号与来源卡片由服务端生成")
    void groundedStatementsGetServerGeneratedNumbers() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "第一篇片段的正文", 1, 3, 5),
                hit("ret-1:2", "chunk-b", "第二篇片段的正文", 2, 8, 9))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        run(List.of(
                knowledgeRound("call-k1", envelope),
                groundedRound("call-a1", "["
                        + "{\"text\":\"第二篇的事实\",\"citationId\":\"ret-1:2\",\"evidenceQuote\":\"第二篇片段\"},"
                        + "{\"text\":\"第一篇的事实\",\"citationId\":\"ret-1:1\",\"evidenceQuote\":\"第一篇片段\"}]")),
                saved, List.of(kb(KB)), envelope, true);

        ChatMessageDTO answer = answerIn(saved);
        // 编号由服务端按 (检索序号, rank) 决定，与陈述的顺序无关。
        assertEquals("第二篇的事实 [2]\n\n第一篇的事实 [1]", answer.getContent());
        assertEquals(2, answer.getMetadata().getCitations().size());
    }

    @Test
    @DisplayName("grounded：原文短引必须来自同一次命中的片段")
    void groundedQuoteMustComeFromTheSameChunk() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "第一篇片段的正文", 1, 3, 5),
                hit("ret-1:2", "chunk-b", "第二篇片段的正文", 2, 8, 9))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        // 短引抄的是另一条命中的原文：整轮判无效，不落库。
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(List.of(
                knowledgeRound("call-k1", envelope),
                groundedRound("call-a1", "["
                        + "{\"text\":\"拼接出来的事实\",\"citationId\":\"ret-1:1\",\"evidenceQuote\":\"第二篇片段\"}]")),
                saved, List.of(kb(KB)), envelope, true));
        assertEquals("INVALID_ANSWER_FORMAT", RunLifecycleService.classify(error));
        assertTrue(saved.stream().noneMatch(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                && message.getContent() != null && !message.getContent().isEmpty()));
    }

    @Test
    @DisplayName("grounded：编造或跨运行的 citationId 一律拒绝")
    void groundedUnknownIdIsRejected() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "第一篇片段的正文", 1, 3, 5))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(List.of(
                knowledgeRound("call-k1", envelope),
                groundedRound("call-a1", "["
                        + "{\"text\":\"编造的事实\",\"citationId\":\"other-run:1\",\"evidenceQuote\":\"第一篇片段\"}]")),
                saved, List.of(kb(KB)), envelope, true));
        assertEquals("INVALID_ANSWER_FORMAT", RunLifecycleService.classify(error));
    }

    @Test
    @DisplayName("grounded：资料不足走独立结果，不挂引用")
    void groundedInsufficientProducesNoCitations() throws Exception {
        List<ChatMessageDTO> saved = new ArrayList<>();
        run(List.of(insufficientRound("call-a1", "资料里没有写客户数量")), saved, List.of(kb(KB)), "",
                true);

        ChatMessageDTO answer = answerIn(saved);
        assertTrue(answer.getContent().startsWith("本次可用资料不足以确认。"));
        assertNull(answer.getMetadata().getCitations());
    }

    @Test
    @DisplayName("启用 grounded 后，directAnswer 不能带引用绕过短引校验")
    void directAnswerCannotCarryCitationsInGroundedMode() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "第一篇片段的正文", 1, 3, 5))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(List.of(
                knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "绕过短引的回答 [1]", "[\"ret-1:1\"]")),
                saved, List.of(kb(KB)), envelope, true));
        assertEquals("INVALID_ANSWER_FORMAT", RunLifecycleService.classify(error));
    }

    @Test
    @DisplayName("grounded 必选：不传 citationIds 的 directAnswer 也不能交付")
    void groundedRequiredRejectsUncitedDirectAnswer() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "第一篇片段的正文", 1, 3, 5))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        // 已真实检索，模型却改走 directAnswer 且省略 citationIds、正文没有编号。
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(List.of(
                knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "依据资料，项目已有1000家客户。", null)),
                saved, List.of(kb(KB)), envelope, true));
        assertEquals("INVALID_ANSWER_FORMAT", RunLifecycleService.classify(error));
        assertTrue(saved.stream().noneMatch(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                && message.getContent() != null && !message.getContent().isEmpty()), "不得落库任何答复正文");
    }

    @Test
    @DisplayName("grounded 必选：纯 assistant.content 也不能当答复")
    void groundedRequiredRejectsPlainContent() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "第一篇片段的正文", 1, 3, 5))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        RuntimeException error = assertThrows(RuntimeException.class, () -> run(List.of(
                knowledgeRound("call-k1", envelope),
                contentRound("依据资料，项目已有1000家客户。")),
                saved, List.of(kb(KB)), envelope, true));
        assertEquals("INVALID_ANSWER_FORMAT", RunLifecycleService.classify(error));
        assertTrue(saved.stream().noneMatch(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                && message.getContent() != null && !message.getContent().isEmpty()), "不得落库任何答复正文");
    }

    @Test
    @DisplayName("旧模式：没有来源的 directAnswer 仍然可以交付（兼容保留）")
    void legacyModeStillDeliversUncitedAnswer() throws Exception {
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "第一篇片段的正文", 1, 3, 5))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        run(List.of(
                knowledgeRound("call-k1", envelope),
                answerRound("call-a1", "依据资料，项目已有1000家客户。", null)),
                saved, List.of(kb(KB)), envelope, false);

        ChatMessageDTO answer = answerIn(saved);
        assertEquals("依据资料，项目已有1000家客户。", answer.getContent());
        assertNull(answer.getMetadata().getCitations(), "没有 citationIds 时不应生成引用");
    }

    @Test
    @DisplayName("grounded：两个 id 指向同一来源时共用编号，不算来源无效")
    void groundedAllowsLegitimateSourceDeduplication() throws Exception {
        // 两次真实登记（同一片段被不同检索 id 返回）：登记器会合成一张来源卡片，两条陈述都该用 [1]。
        String envelope = envelope(RetrievalEnvelope.ok("ret-1", List.of(
                hit("ret-1:1", "chunk-a", "第一篇片段的正文", 1, 3, 5),
                hit("ret-1:2", "chunk-a", "第一篇片段的正文", 2, 3, 5))));
        List<ChatMessageDTO> saved = new ArrayList<>();
        run(List.of(
                knowledgeRound("call-k1", envelope),
                groundedRound("call-a1", "["
                        + "{\"text\":\"第一条事实\",\"citationId\":\"ret-1:1\",\"evidenceQuote\":\"第一篇片段\"},"
                        + "{\"text\":\"第二条事实\",\"citationId\":\"ret-1:2\",\"evidenceQuote\":\"第一篇片段\"}]")),
                saved, List.of(kb(KB)), envelope, true);

        ChatMessageDTO answer = answerIn(saved);
        assertEquals("第一条事实 [1]\n\n第二条事实 [1]", answer.getContent(), "同一来源必须共用同一个编号");
        assertEquals(1, answer.getMetadata().getCitations().size(), "同一来源只生成一张来源卡片");
    }


    // ------------------------------------------------------------------ fixtures

    /** 用脚本响应运行一次真实执行循环，返回本次运行的运行时对象以便检查内部登记表。 */
    private static ScriptedRuntime run(List<ChatResponse> script, List<ChatMessageDTO> saved,
                                       List<KnowledgeBaseDTO> knowledgeBases, String retrievalEnvelope) {
        return run(script, saved, knowledgeBases, retrievalEnvelope, false);
    }

    /** grounded=true 时把结构化交付工具也装上，启用新协议运行。 */
    private static ScriptedRuntime run(List<ChatResponse> script, List<ChatMessageDTO> saved,
                                       List<KnowledgeBaseDTO> knowledgeBases, String retrievalEnvelope,
                                       boolean grounded) {
        ChatMessageFacadeService messages = mock(ChatMessageFacadeService.class);
        when(messages.createChatMessage(any(ChatMessageDTO.class))).thenAnswer(invocation -> {
            ChatMessageDTO dto = invocation.getArgument(0);
            saved.add(dto);
            return CreateChatMessageResponse.builder().chatMessageId("message-" + saved.size()).build();
        });
        List<Object> toolObjects = new ArrayList<>();
        toolObjects.add(new SyntheticKnowledgeTool(retrievalEnvelope));
        toolObjects.add(new DirectAnswerTool());
        if (grounded) {
            toolObjects.add(new GroundedAnswerTool());
        }
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects.toArray())
                .build()
                .getToolCallbacks();
        ScriptedRuntime runtime = new ScriptedRuntime(script, "agent", "Agent", "描述", "系统提示",
                mock(ChatClient.class), 20, List.of(), List.of(callbacks), knowledgeBases, "session",
                mock(SseService.class), messages, new ChatMessageConverter(JSON), () -> { }, "用户问题");
        // 真实 ChatClient 会把工具回调放进这次模型调用的选项（Agent 自己的 chatOptions 因此带上它们）；
        // 脚本化回放不经过网络，这里显式给出同一份选项，让 execute() 里的 ToolCallingManager 解析到同样的工具。
        ReflectionTestUtils.setField(runtime, "chatOptions", DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolCallbacks(callbacks)
                .build());
        runtime.run();
        return runtime;
    }

    private static ChatMessageDTO answerIn(List<ChatMessageDTO> saved) {
        return saved.stream()
                .filter(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                        && !message.getContent().isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no answer message was persisted: " + saved));
    }

    private static ChatResponse knowledgeRound(String toolCallId, String envelope) {
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(toolCallId, "function", "KnowledgeTool",
                        "{\"kbsId\":\"" + KB + "\",\"query\":\"问题\"}")))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse answerRound(String toolCallId, String answer, String citationIdsJson) throws Exception {
        var parameters = JSON.createObjectNode().put("answer", answer);
        if (citationIdsJson != null) parameters.set("citationIds", JSON.readTree(citationIdsJson));
        String arguments = JSON.writeValueAsString(parameters);
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(toolCallId, "function", "directAnswer", arguments)))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    /** 不调任何交付工具、直接把答案放进 content 的一轮。 */
    private static ChatResponse contentRound(String content) {
        AssistantMessage message = AssistantMessage.builder().content(content).toolCalls(List.of()).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    /** grounded-v1 交付轮：statements 每项 {text, citationId, evidenceQuote}。 */
    private static ChatResponse groundedRound(String toolCallId, String statementsJson) throws Exception {
        var parameters = JSON.createObjectNode();
        parameters.set("statements", JSON.readTree(statementsJson));
        return groundedCall(toolCallId, parameters);
    }

    /** grounded-v1 资料不足轮。 */
    private static ChatResponse insufficientRound(String toolCallId, String reason) throws Exception {
        return groundedCall(toolCallId, JSON.createObjectNode().put("insufficient", reason));
    }

    private static ChatResponse groundedCall(String toolCallId, com.fasterxml.jackson.databind.node.ObjectNode parameters)
            throws Exception {
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(toolCallId, "function", "groundedAnswer",
                        JSON.writeValueAsString(parameters))))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    /** 用队列里的脚本替换真实模型调用；think/execute/saveMessage/引用校验都保持生产实现。 */
    private static final class ScriptedRuntime extends JChatMind {
        private final Queue<ChatResponse> responses;

        ScriptedRuntime(List<ChatResponse> script, String agentId, String name, String description, String systemPrompt,
                        ChatClient chatClient, int maxMessages, List<Message> memory, List<ToolCallback> tools,
                        List<KnowledgeBaseDTO> knowledgeBases, String chatSessionId, SseService sseService,
                        ChatMessageFacadeService chatMessageFacadeService, ChatMessageConverter chatMessageConverter,
                        Runnable runtimeGuard, String submittedUserInput) {
            super(agentId, name, description, systemPrompt, chatClient, maxMessages, memory, tools, knowledgeBases,
                    chatSessionId, sseService, chatMessageFacadeService, chatMessageConverter, runtimeGuard,
                    submittedUserInput);
            this.responses = new ConcurrentLinkedQueue<>(script);
        }

        @Override
        ChatResponse requestModel(Prompt prompt, String thinkPrompt) {
            ChatResponse response = responses.poll();
            assertNotNull(response, "the scripted run asked for more model calls than the script provides");
            return response;
        }

        boolean registeredCitations() {
            CitationRegistry registry = (CitationRegistry) ReflectionTestUtils.getField(this, "citationRegistry");
            return registry != null && !registry.isEmpty();
        }
    }

    private static KnowledgeBaseDTO kb(String id) {
        return KnowledgeBaseDTO.builder().id(id).name("合成知识库").build();
    }

    private static RetrievalHit hit(String citationId, String chunkId, String content, int rank, int start, int end) {
        return new RetrievalHit(citationId, chunkId, KB, rank, content,
                new SourceLocation(DOC, "《合成资料》", List.of("章节"), "hash-" + chunkId, start, end),
                "body-v1", "rev-1", RetrievalHit.METRIC_L2, 0.5);
    }

    private static String envelope(RetrievalEnvelope envelope) throws Exception {
        return JSON.writeValueAsString(envelope);
    }

    /** 合成检索工具：与生产工具同名同参，返回真实的契约信封结构（只作为 @Tool 方法提供者，不实现 Tool）。 */
    public static final class SyntheticKnowledgeTool {
        private final String envelope;

        public SyntheticKnowledgeTool(String envelope) {
            this.envelope = envelope;
        }

        @Tool(name = "KnowledgeTool", description = "合成检索结果")
        public String knowledgeQuery(String kbsId, String query) {
            return envelope;
        }
    }
}
