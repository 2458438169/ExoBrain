package com.kama.jchatmind.demo;

import com.kama.jchatmind.agent.AgentState;
import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.agent.tools.DirectAnswerTool;
import com.kama.jchatmind.agent.tools.TerminateTool;
import com.kama.jchatmind.agent.tools.test.DateTool;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.execution.RunLifecycleService;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.retrieval.RetrievalService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.impl.ToolFacadeServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Public demo has its own tool allowlist and a knowledge search restricted to published IDs. */
@Service
@Slf4j
public class DemoExecutionService {
    private final JChatMindFactory factory;
    private final Executor executor;
    private final SseService sse;
    private final DemoAccessPolicy access;
    private final RunLifecycleService runs;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public DemoExecutionService(ChatClientRegistry clients, SseService sse, AgentMapper agents,
                                AgentConverter agentConverter, KnowledgeBaseMapper knowledgeBases,
                                KnowledgeBaseConverter knowledgeConverter, ChatMessageFacadeService messages,
                                ChatMessageConverter messageConverter, DateTool date, TerminateTool terminate,
                                DirectAnswerTool answer, RetrievalService retrieval, DemoAccessPolicy access,
                                @Qualifier("taskExecutor") Executor executor, RunLifecycleService runs) {
        this.factory = new JChatMindFactory(clients, sse, agents, agentConverter, knowledgeBases,
                knowledgeConverter, new ToolFacadeServiceImpl(List.of(date, terminate, answer,
                new DemoKnowledgeTool(retrieval, access))), messages, messageConverter);
        this.executor = executor;
        this.sse = sse;
        this.access = access;
        this.runs = runs;
    }

    public boolean isRunning(String sessionId) { return running.contains(sessionId); }

    public CreateChatMessageResponse submit(String agentId, String sessionId,
                                            Supplier<CreateChatMessageResponse> persistUserMessage) {
        if (!running.add(sessionId)) throw new DemoException(409, "上一条消息仍在处理中，请等待完成");
        try {
            CreateChatMessageResponse response = persistUserMessage.get();
            // 游客运行：主记录创建失败就不启动执行，也不假装已接入追踪。
            RunLifecycleService.RunStart run;
            try {
                run = runs.startDemoRun(sessionId, response.getChatMessageId(), agentId);
            } catch (RuntimeException unavailable) {
                log.error("Demo run record could not be created; the chat execution was not started: session={}",
                        sessionId);
                throw new DemoException(503, "执行记录不可用，请稍后重试");
            }
            executor.execute(() -> {
                try {
                    JChatMind runtime = factory.create(agentId, sessionId, access::validateAgent);
                    runtime.attachRun(run.recorder());
                    runtime.run();
                    if (runtime.state() == AgentState.ERROR) {
                        runs.failed(run, "NO_ANSWER_PRODUCED");
                    } else {
                        runs.succeeded(run);
                    }
                } catch (Exception error) {
                    runs.failed(run, RunLifecycleService.classify(error));
                    log.warn("Demo execution failed: session={}", sessionId, error);
                    try {
                        sse.send(sessionId, SseMessage.builder().type(SseMessage.Type.AI_DONE)
                                .payload(SseMessage.Payload.builder().done(true).statusText("执行失败，请稍后重试").build()).build());
                    } catch (RuntimeException ignored) { }
                } finally {
                    running.remove(sessionId);
                }
            });
            return response;
        } catch (RuntimeException error) {
            running.remove(sessionId);
            throw error;
        }
    }
}