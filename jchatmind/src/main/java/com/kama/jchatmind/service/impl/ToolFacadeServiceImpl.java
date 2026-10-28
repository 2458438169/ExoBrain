package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import com.kama.jchatmind.service.ToolFacadeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ToolFacadeServiceImpl implements ToolFacadeService {

    private static final String GROUNDED_ANSWER_TOOL = "groundedAnswer";

    private final List<Tool> tools;
    private final boolean groundedAnswerEnabled;

    public ToolFacadeServiceImpl(List<Tool> tools,
                                 @Value("${app.grounded-answer.enabled:false}") boolean groundedAnswerEnabled) {
        this.tools = tools;
        // 新协议是显式开关：默认关闭，隔离环境里打开后才出现 groundedAnswer 工具。
        this.groundedAnswerEnabled = groundedAnswerEnabled;
    }

    @Override
    public List<Tool> getAllTools() {
        return getToolsByType(null);
    }

    @Override
    public List<Tool> getOptionalTools() {
        return getToolsByType(ToolType.OPTIONAL);
    }

    @Override
    public List<Tool> getFixedTools() {
        return getToolsByType(ToolType.FIXED);
    }

    private List<Tool> getToolsByType(ToolType type) {
        return tools.stream()
                .filter(tool -> type == null || tool.getType().equals(type))
                .filter(this::allowed)
                .toList();
    }

    private boolean allowed(Tool tool) {
        return groundedAnswerEnabled || !GROUNDED_ANSWER_TOOL.equals(tool.getName());
    }
}
