package com.kama.jchatmind.agent;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 模型输入投影：本轮只处理当前提问，历史只用于理解指代。
 *
 * <p>失败/取消/中断的运行只留用户消息、不写回答。这类「悬空的旧请求」如果留在上下文里，
 * 模型会把上一题一起答掉（实测 Q14 串 Q13、Q21 串 Q20）。</p>
 */
class JChatMindFactoryMemoryTest {

    private static ChatMessageDTO user(String content) {
        return ChatMessageDTO.builder().role(ChatMessageDTO.RoleType.USER).content(content).build();
    }

    private static ChatMessageDTO assistant(String content) {
        return ChatMessageDTO.builder().role(ChatMessageDTO.RoleType.ASSISTANT).content(content).build();
    }

    private static List<String> texts(List<Message> memory) {
        List<String> texts = new ArrayList<>();
        for (Message message : memory) {
            texts.add(message.getText());
        }
        return texts;
    }

    @Test
    @DisplayName("上一题失败后没有回答，它不进入本轮输入")
    void unansweredPreviousRequestIsNotProjected() {
        List<Message> memory = JChatMindFactory.restoreMemory(List.of(
                user("这个项目服务了多少客户、节省了多少成本？"),
                user("作者之前在哪家公司做后端？")));

        assertEquals(List.of("作者之前在哪家公司做后端？"), texts(memory));
    }

    @Test
    @DisplayName("答过的历史保留，显式追问仍能引用上一轮")
    void answeredHistoryIsKeptForFollowUps() {
        List<Message> memory = JChatMindFactory.restoreMemory(List.of(
                user("什么是这项工程里的 fencing token？"),
                assistant("fencing 是每次领取任务都会前进的一个计数。"),
                user("展开第 2 点")));

        assertEquals(3, memory.size());
        assertEquals("展开第 2 点", ((UserMessage) memory.get(2)).getText());
    }

    @Test
    @DisplayName("中间那一题失败时，只丢掉失败的那条用户消息")
    void onlyTheUnansweredRequestIsDropped() {
        List<Message> memory = JChatMindFactory.restoreMemory(List.of(
                user("第一个问题"),
                assistant("第一个回答"),
                user("第二个问题（运行失败，没有回答）"),
                user("第三个问题")));

        assertEquals(List.of("第一个问题", "第一个回答", "第三个问题"), texts(memory));
    }
}