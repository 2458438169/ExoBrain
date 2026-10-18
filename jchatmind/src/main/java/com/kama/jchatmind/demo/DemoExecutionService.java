package com.kama.jchatmind.demo;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.agent.tools.DirectAnswerTool;
import com.kama.jchatmind.agent.tools.TerminateTool;
import com.kama.jchatmind.agent.tools.test.DateTool;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.execution.RunOrigin;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.retrieval.RetrievalService;
import com.kama.jchatmind.runs.RunQueueRepository;
import com.kama.jchatmind.runs.RunSubmissionException;
import com.kama.jchatmind.runs.RunSubmissionService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.impl.ToolFacadeServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * 游客侧：与账号一样的持久队列提交，以及公开运行时的构造。
 *
 * <p>提交走 {@link RunSubmissionService#submitDemo}：用户消息、固定快照、运行摘要与任务同一事务落库，
 * 游客只保存访客 Cookie 的 SHA-256（{@code demoOwner}），不保存原始 Cookie。执行由数据库队列领取，
 * 不再是进程内的线程池 + 内存集合。公开运行时仍只注册 {@link DemoAccessPolicy#PUBLIC_TOOL_NAMES}
 * 中的四个工具；管理员/账号配置不会进入游客运行。</p>
 */
@Service
@Slf4j
public class DemoExecutionService {
    private final JChatMindFactory factory;
    private final DemoAccessPolicy access;
    private final RunSubmissionService submissions;
    private final RunQueueRepository jobs;

    public DemoExecutionService(ChatClientRegistry clients, SseService sse, AgentMapper agents,
                                AgentConverter agentConverter, KnowledgeBaseMapper knowledgeBases,
                                KnowledgeBaseConverter knowledgeConverter, ChatMessageFacadeService messages,
                                ChatMessageConverter messageConverter, DateTool date, TerminateTool terminate,
                                DirectAnswerTool answer, RetrievalService retrieval, DemoAccessPolicy access,
                                RunSubmissionService submissions, RunQueueRepository jobs) {
        this.factory = new JChatMindFactory(clients, sse, agents, agentConverter, knowledgeBases,
                knowledgeConverter, new ToolFacadeServiceImpl(List.of(date, terminate, answer,
                new DemoKnowledgeTool(retrieval, access))), messages, messageConverter);
        this.access = access;
        this.submissions = submissions;
        this.jobs = jobs;
    }

    /** 该会话是否仍有排队/执行中的任务（删除会话前检查；来自数据库，不是进程内 Set）。 */
    public boolean isRunning(String sessionId) {
        return jobs.findActiveBySession(sessionId).isPresent();
    }

    /** 游客提交：与账号共用同一个持久队列；重复提交由幂等键返回原来的 message/run。 */
    public CreateChatMessageResponse submit(String owner, String agentId, String sessionId, String content,
                                            String idempotencyKey) {
        try {
            return submissions.submitDemo(owner, agentId, sessionId, content, idempotencyKey).toResponse();
        } catch (RunSubmissionException rejected) {
            throw new DemoException(rejected.status(), rejected.getMessage());
        } catch (DataIntegrityViolationException concurrentConflict) {
            try {
                return submissions.replayAfterConflict(RunOrigin.DEMO,
                        RunSubmissionService.actorScope(RunOrigin.DEMO, owner), sessionId, idempotencyKey,
                        RunSubmissionService.requestDigest(RunOrigin.DEMO, sessionId, agentId, content))
                        .toResponse();
            } catch (RunSubmissionException conflict) {
                throw new DemoException(conflict.status(), conflict.getMessage());
            }
        }
    }

    /** 公开运行时：固定的四工具白名单 + 公开助手校验。 */
    public JChatMind runtime(String agentId, String sessionId, Consumer<AgentDTO> validateConfig) {
        return factory.create(agentId, sessionId, validateConfig);
    }

    public DemoAccessPolicy policy() {
        return access;
    }
}
