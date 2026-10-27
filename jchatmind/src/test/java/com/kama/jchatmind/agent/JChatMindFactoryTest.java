package com.kama.jchatmind.agent;

import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolFacadeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindFactoryTest {

    @Test
    @Timeout(15)
    void concurrentCreatesRetainEachAgentsMemoryWindow() throws Exception {
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentConverter converter = mock(AgentConverter.class);
        ChatClientRegistry clients = mock(ChatClientRegistry.class);
        ChatMessageFacadeService messages = mock(ChatMessageFacadeService.class);
        ToolFacadeService tools = mock(ToolFacadeService.class);
        when(tools.getFixedTools()).thenReturn(List.of());
        when(clients.get("deepseek-chat")).thenReturn(mock(ChatClient.class));

        Agent first = Agent.builder().id("first").model("deepseek-chat").build();
        Agent second = Agent.builder().id("second").model("deepseek-chat").build();
        when(agentMapper.selectById("first")).thenReturn(first);
        when(agentMapper.selectById("second")).thenReturn(second);
        when(converter.toDTO(first)).thenReturn(configWithWindow(2));
        when(converter.toDTO(second)).thenReturn(configWithWindow(4));

        // 每问配一条回答：未被回答的用户消息按新投影规则不会进入模型输入，
        // 而本用例要检验的是「各自保留自己的记忆窗口」，所以历史必须是答过的。
        List<ChatMessageDTO> history = IntStream.rangeClosed(1, 5)
                .boxed()
                .flatMap(i -> Stream.of(
                        ChatMessageDTO.builder()
                                .role(ChatMessageDTO.RoleType.USER).content("message-" + i).build(),
                        ChatMessageDTO.builder()
                                .role(ChatMessageDTO.RoleType.ASSISTANT).content("answer-" + i).build()))
                .toList();
        CountDownLatch firstLoading = new CountDownLatch(1);
        CountDownLatch resumeFirst = new CountDownLatch(1);
        when(messages.getChatMessagesBySessionIdRecently("session-first", 2)).thenAnswer(invocation -> {
            firstLoading.countDown();
            assertTrue(resumeFirst.await(5, TimeUnit.SECONDS));
            return history;
        });
        when(messages.getChatMessagesBySessionIdRecently("session-second", 4)).thenReturn(history);

        JChatMindFactory factory = new JChatMindFactory(clients, mock(SseService.class),
                agentMapper, converter, mock(KnowledgeBaseMapper.class),
                mock(KnowledgeBaseConverter.class), tools, messages, mock(ChatMessageConverter.class));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var pendingFirst = executor.submit(() -> factory.create("first", "session-first"));
            assertTrue(firstLoading.await(5, TimeUnit.SECONDS));
            JChatMind secondRuntime = factory.create("second", "session-second");
            resumeFirst.countDown();
            JChatMind firstRuntime = pendingFirst.get(5, TimeUnit.SECONDS);

            assertEquals(List.of("message-5", "answer-5"), memoryTexts(firstRuntime, "session-first"));
            assertEquals(List.of("message-4", "answer-4", "message-5", "answer-5"),
                    memoryTexts(secondRuntime, "session-second"));
            verify(messages).getChatMessagesBySessionIdRecently("session-first", 2);
            verify(messages).getChatMessagesBySessionIdRecently("session-second", 4);
        } finally {
            resumeFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static AgentDTO configWithWindow(int size) {
        return AgentDTO.builder().allowedKbs(List.of()).allowedTools(List.of())
                .chatOptions(AgentDTO.ChatOptions.builder().messageLength(size).build()).build();
    }

    private static List<String> memoryTexts(JChatMind runtime, String sessionId) {
        ChatMemory memory = (ChatMemory) ReflectionTestUtils.getField(runtime, "chatMemory");
        return memory.get(sessionId).stream().map(message -> message.getText()).toList();
    }
}
