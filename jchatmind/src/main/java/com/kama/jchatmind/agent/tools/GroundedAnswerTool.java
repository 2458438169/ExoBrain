package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;

import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 结构化交付（grounded-v1）：模型只给「陈述 + citationId + 原文短引」，编号由服务端生成。
 *
 * <p>与 {@code directAnswer} 并存：旧协议与历史消息保持兼容。启用的运行里，
 * 带引用的知识回答只能走本通道（见 JChatMind 的 grounded 分支），不能退回旧通道绕过短引校验。</p>
 */
@Component
public class GroundedAnswerTool implements Tool {

    /** 模型交付的一条陈述。 */
    public record Statement(
            @ToolParam(description = "一句可独立核对的陈述，只表达一件事") String text,
            @ToolParam(description = "本轮真实检索命中的完整 citationId，原样复制，不要改写") String citationId,
            @ToolParam(description = "上面这个命中片段里的一段连续原文，用于服务端核对；不要改写或拼接") String evidenceQuote) {
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "groundedAnswer",
            description = "结构化交付答复：每条陈述都绑定一个本轮检索命中的 citationId 与其中一段连续原文。"
                    + "正文编号由服务端生成，你不要写 [n]。默认最多三条陈述，一条只表达一件事；"
                    + "结论里的事实也必须写进陈述，不要在别处补充没有绑定的细节。"
                    + "如果本次可用资料不足以回答，改用 insufficient 参数说明缺什么，不要挂不相关的引用。"
    )
    public String groundedAnswer(
            @ToolParam(required = false, description = "陈述列表；资料足够时必填") List<Statement> statements,
            @ToolParam(required = false, description = "资料不足时的简短原因；与 statements 二选一") String insufficient) {
        // 与 DirectAnswerTool 一样只回确认：真正的装配由 JChatMind 从 arguments 读取并校验。
        return "答复已交付";
    }

    @Override
    public String getName() {
        return "groundedAnswer";
    }

    @Override
    public String getDescription() {
        return "结构化交付：陈述 + citationId + 原文短引，由服务端生成编号";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }
}