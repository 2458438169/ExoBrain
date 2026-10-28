package com.kama.jchatmind.agent;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入投影：本轮只执行最后一条用户消息；未答上的旧问题是**被动上下文**，不是待办。
 *
 * <p>失败、取消、中断都不会写回答。简单丢弃它们会让「把刚才的问题再答一次」失去指代对象，
 * 所以未答上的旧轮只保留用户消息，并在历史与本轮之间插入一条系统说明。</p>
 */
class JChatMindFactoryMemoryTest {

    private static ChatMessageDTO user(String id, String content) {
        return ChatMessageDTO.builder().id(id).role(ChatMessageDTO.RoleType.USER).content(content).build();
    }

    private static ChatMessageDTO assistant(String id, String content) {
        return ChatMessageDTO.builder().id(id).role(ChatMessageDTO.RoleType.ASSISTANT).content(content).build();
    }

    private static ChatMessageDTO tool(String id) {
        return ChatMessageDTO.builder().id(id).role(ChatMessageDTO.RoleType.TOOL)
                .content("tool result")
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolResponse(new ToolResponseMessage.ToolResponse("call-1", "knowledge", "片段"))
                        .build())
                .build();
    }

    private static ChatMessageDTO toolCallOnly(String id) {
        return ChatMessageDTO.builder().id(id).role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("")
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "knowledge", "{}")))
                        .build())
                .build();
    }

    private static List<String> texts(List<Message> memory) {
        List<String> texts = new ArrayList<>();
        for (Message message : memory) {
            texts.add(message.getText());
        }
        return texts;
    }

    private static boolean hasPassiveNote(List<Message> memory) {
        return memory.stream().anyMatch(message -> message instanceof SystemMessage
                && message.getText().contains("不是本轮待办"));
    }

    @Test
    @DisplayName("前题失败、当前改问无关问题：旧题只作上下文，本轮是唯一执行意图")
    void unansweredPreviousQuestionIsContextNotTask() {
        JChatMindFactory.MemoryProjection projection = JChatMindFactory.project(List.of(
                user("m1", "这个项目服务了多少客户、节省了多少成本？"),
                user("m2", "作者之前在哪家公司做后端？")));

        List<String> texts = texts(projection.messages());
        assertTrue(texts.contains("这个项目服务了多少客户、节省了多少成本？"), "旧题要留在上下文里");
        assertTrue(texts.contains("作者之前在哪家公司做后端？"), "本轮问题必须在");
        assertTrue(hasPassiveNote(projection.messages()), "要有「不是本轮待办」的系统说明");
        assertEquals("current-task", reason(projection, "m2"));
        assertEquals("previous-unanswered:context", reason(projection, "m1"));
    }

    @Test
    @DisplayName("前题失败、当前明确重试：原问题仍可被理解（指代对象没丢）")
    void explicitRetryKeepsTheOriginalQuestionAsAntecedent() {
        JChatMindFactory.MemoryProjection projection = JChatMindFactory.project(List.of(
                user("m1", "解释一下 fencing token 如何防止迟到写入。"),
                user("m2", "把刚才的问题再答一次。")));

        List<String> texts = texts(projection.messages());
        assertEquals(List.of("解释一下 fencing token 如何防止迟到写入。", "把刚才的问题再答一次。"),
                texts.stream().filter(text -> !text.contains("不是本轮待办")).toList());
        assertTrue(texts.get(0).contains("fencing token"), "原问题要在追问之前出现");
    }

    @Test
    @DisplayName("前题已回答、当前追问：正常多轮保留，不加被动说明")
    void answeredHistoryKeepsNormalMultiTurn() {
        JChatMindFactory.MemoryProjection projection = JChatMindFactory.project(List.of(
                user("m1", "什么是这项工程里的 fencing token？"),
                assistant("a1", "fencing 是每次领取任务都会前进的一个计数。"),
                user("m2", "展开第 2 点")));

        assertEquals(List.of("什么是这项工程里的 fencing token？",
                "fencing 是每次领取任务都会前进的一个计数。", "展开第 2 点"),
                texts(projection.messages()));
        assertFalse(hasPassiveNote(projection.messages()));
        assertEquals("answered-history", reason(projection, "a1"));
    }

    @Test
    @DisplayName("前题执行了检索但整轮失败：助手决策与旧工具结果都不进入投影")
    void failedRoundWithToolsDropsAssistantAndToolMessages() {
        JChatMindFactory.MemoryProjection projection = JChatMindFactory.project(List.of(
                user("m1", "检索一下这个项目的评测指标。"),
                toolCallOnly("a1"),
                tool("t1"),
                user("m2", "作者之前在哪家公司做后端？")));

        List<Message> memory = projection.messages();
        assertTrue(memory.stream().noneMatch(message -> message instanceof ToolResponseMessage),
                "旧工具结果不能留下");
        assertTrue(memory.stream().noneMatch(message -> message instanceof AssistantMessage),
                "没有配对回答的助手决策不能留下");
        assertEquals("unanswered-round:dropped-with-tools", reason(projection, "t1"));
        assertEquals("unanswered-round:dropped-with-tools", reason(projection, "a1"));
    }

    @Test
    @DisplayName("取消/中断后的无关新问题与显式续问，行为与失败一致")
    void cancelledOrInterruptedRoundsBehaveLikeFailures() {
        // 取消与中断同样不写回答，投影只看「这一轮有没有正文回答」。
        JChatMindFactory.MemoryProjection unrelated = JChatMindFactory.project(List.of(
                user("m1", "一个任务变慢会连累其他任务吗？"),
                user("m2", "现在是不是已经逐字流式输出？")));
        assertTrue(hasPassiveNote(unrelated.messages()));
        assertEquals("current-task", reason(unrelated, "m2"));

        JChatMindFactory.MemoryProjection retry = JChatMindFactory.project(List.of(
                user("m1", "一个任务变慢会连累其他任务吗？"),
                user("m2", "继续上一个问题")));
        assertTrue(texts(retry.messages()).contains("一个任务变慢会连累其他任务吗？"));
    }

    private static String reason(JChatMindFactory.MemoryProjection projection, String messageId) {
        return projection.decisions().stream()
                .filter(decision -> decision.messageId().equals(messageId))
                .map(JChatMindFactory.Decision::reason)
                .findFirst()
                .orElse("missing");
    }

    @Test
    @DisplayName("窗口裁掉了提问、只剩回答时，这些孤儿消息不进入投影并记录理由")
    void orphanAnswerOutsideWindowIsRecordedNotProjected() {
        JChatMindFactory.MemoryProjection projection = JChatMindFactory.project(List.of(
                assistant("a0", "上一轮的回答（它的提问已落在窗口外）"),
                tool("t0"),
                user("m2", "把刚才的问题再答一次。")));

        List<Message> memory = projection.messages();
        assertEquals(1, memory.size(), "只有本轮问题进入投影");
        assertEquals("把刚才的问题再答一次。", memory.get(0).getText());
        assertEquals("window-truncated:user-message-outside-window", reason(projection, "a0"));
        assertEquals("window-truncated:user-message-outside-window", reason(projection, "t0"));
    }
}