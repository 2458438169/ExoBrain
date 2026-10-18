package com.kama.jchatmind.runs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.auth.ResourceAccess;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.demo.DemoAccessPolicy;
import com.kama.jchatmind.demo.DemoSessionRepository;
import com.kama.jchatmind.execution.RunRepository;
import com.kama.jchatmind.execution.RunSummary;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.models.ModelException;
import org.springframework.stereotype.Service;

/**
 * 排队工作开始前的重新校验（不依赖请求线程的身份）。
 *
 * <p>后台线程没有 {@code AuthContext}，因此排队任务自带提交时的服务端校验信息：
 * 账号用 {@code (actorId, authVersion, sessionHash)} 重新走 {@link ResourceAccess#requireExecutionActor}，
 * 游客用 owner 哈希重新确认会话仍然属于这个访客。账号停用、会话撤销、助手/KB 权限变化、
 * 模型停用都会在这里失败，而不会因为“已经入队”被绕过。</p>
 *
 * <p>配置版本：运行记录里保存了提交时的 {@code agentConfigHash}；领取时重新计算，变化即拒绝
 * （{@code AGENT_CONFIG_CHANGED}），绝不静默切换到另一个模型或工具配置。</p>
 */
@Service
public class RunAuthorization {

    private final ResourceAccess access;
    private final DemoAccessPolicy demo;
    private final DemoSessionRepository demoSessions;
    private final AgentMapper agents;
    private final AgentConverter converter;
    private final ModelCatalogService models;
    private final RunRepository runs;

    public RunAuthorization(ResourceAccess access, DemoAccessPolicy demo, DemoSessionRepository demoSessions,
                            AgentMapper agents, AgentConverter converter, ModelCatalogService models,
                            RunRepository runs) {
        this.access = access;
        this.demo = demo;
        this.demoSessions = demoSessions;
        this.agents = agents;
        this.converter = converter;
        this.models = models;
        this.runs = runs;
    }

    /**
     * 重新校验身份与会话归属，然后返回当前助手配置（未裁剪，由执行器按来源决定是否限制工具）。
     *
     * @throws RunRejectedException 身份/会话/助手/模型已经不可用，或配置版本已变化
     */
    public AgentDTO revalidate(RunQueueJob job) {
        requireIdentity(job);
        if (job.agentId() == null) {
            throw new RunRejectedException("AGENT_UNAVAILABLE", "助手不可用", true);
        }
        Agent agent;
        try {
            agent = agents.selectById(job.agentId());
        } catch (RuntimeException unavailable) {
            throw new RunRejectedException("AGENT_UNAVAILABLE", "助手配置读取失败", false);
        }
        if (agent == null) {
            throw new RunRejectedException("AGENT_UNAVAILABLE", "助手已删除", true);
        }
        String expected = null;
        RunSummary run = runs.findById(job.runId());
        if (run != null) {
            expected = run.agentConfigHash();
        }
        String current = RunConfigHash.of(agent);
        if (expected != null && !expected.equals(current)) {
            throw new RunRejectedException("AGENT_CONFIG_CHANGED",
                    "助手配置在排队期间发生变化，本次运行已明确拒绝而不是换用新配置", false);
        }
        try {
            models.validateLlmSelection(agent.getModel());
        } catch (ModelException unavailable) {
            throw new RunRejectedException("MODEL_UNAVAILABLE", "模型不可用或已停用", false);
        }
        try {
            return converter.toDTO(agent);
        } catch (JsonProcessingException invalid) {
            throw new RunRejectedException("AGENT_CONFIG_INVALID", "助手配置不可用", false);
        }
    }

    /** 只校验身份与会话归属（执行器在每次检查点也会再次调用 {@link #requireIdentity}）。 */
    public void requireIdentity(RunQueueJob job) {
        if (job.origin() == com.kama.jchatmind.execution.RunOrigin.ACCOUNT) {
            if (job.actorUserId() == null || job.actorAuthVersion() == null || job.actorSessionHash() == null) {
                throw new RunRejectedException("AUTHORIZATION_REVOKED", "账号身份不完整", false);
            }
            try {
                access.requireExecutionActor(job.actorUserId(), job.actorAuthVersion(), job.actorSessionHash(),
                        job.sessionId(), job.agentId());
            } catch (RuntimeException revoked) {
                throw new RunRejectedException("AUTHORIZATION_REVOKED", "账号或会话已不可用", false);
            }
            return;
        }
        if (job.demoOwner() == null || demoSessions.find(job.sessionId(), demo.agentId(), job.demoOwner()) == null) {
            throw new RunRejectedException("DEMO_SESSION_REVOKED", "体验会话已不可用", true);
        }
    }
}
