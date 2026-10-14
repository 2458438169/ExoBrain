package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.CreateAgentRequest;
import com.kama.jchatmind.model.request.UpdateAgentRequest;
import com.kama.jchatmind.model.response.CreateAgentResponse;
import com.kama.jchatmind.model.response.GetAgentsResponse;
import com.kama.jchatmind.service.AgentFacadeService;
import com.kama.jchatmind.auth.ResourceAccess;
import com.kama.jchatmind.auth.AuthException;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.model.vo.AgentVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class AgentController {

    private final AgentFacadeService agentFacadeService;
    private final ResourceAccess access;
    private final AgentConverter converter;

    // 查询 agents
    @GetMapping("/agents")
    public ApiResponse<GetAgentsResponse> getAgents() {
        var response = agentFacadeService.getAgents();
        response.setAgents(Arrays.stream(response.getAgents()).filter(agent -> access.canRead(ResourceAccess.AGENT, agent.getId()))
                .peek(agent -> {
                    boolean readOnly = !access.canWrite(ResourceAccess.AGENT, agent.getId());
                    agent.setReadOnly(readOnly);
                    if (readOnly) {
                        agent.setSystemPrompt(null);
                        agent.setAllowedTools(List.of());
                        agent.setAllowedKbs(agent.getAllowedKbs() == null ? List.of()
                                : agent.getAllowedKbs().stream().filter(kb -> access.canRead(ResourceAccess.KB, kb)).toList());
                    }
                }).toArray(AgentVO[]::new));
        return ApiResponse.success(response);
    }

    // 创建 agent
    @PostMapping("/agents")
    @Transactional
    public ApiResponse<CreateAgentResponse> createAgent(@RequestBody CreateAgentRequest request) {
        access.validateAgentConfiguration(converter.toDTO(request));
        var response = agentFacadeService.createAgent(request);
        access.claim(ResourceAccess.AGENT, response.getAgentId());
        return ApiResponse.success(response);
    }

    // 删除 agent
    @DeleteMapping("/agents/{agentId}")
    @Transactional
    public ApiResponse<Void> deleteAgent(@PathVariable String agentId) {
        access.requireAgent(agentId, true);
        agentFacadeService.deleteAgent(agentId);
        access.remove(ResourceAccess.AGENT, agentId);
        return ApiResponse.success();
    }

    // 更新 agent
    @PatchMapping("/agents/{agentId}")
    public ApiResponse<Void> updateAgent(@PathVariable String agentId, @RequestBody UpdateAgentRequest request) {
        try {
            var config = converter.toDTO(access.requireAgent(agentId, true));
            converter.updateDTOFromRequest(config, request);
            access.validateAgentConfiguration(config);
        } catch (JsonProcessingException error) { throw new AuthException(400, "助手配置无效"); }
        agentFacadeService.updateAgent(agentId, request);
        return ApiResponse.success();
    }
}
