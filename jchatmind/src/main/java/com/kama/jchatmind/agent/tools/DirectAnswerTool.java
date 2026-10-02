package com.kama.jchatmind.agent.tools;

import org.springframework.stereotype.Component;

@Component
public class DirectAnswerTool implements Tool {

    @Override
    public String getName() {
        return "directAnswer";
    }

    @Override
    public String getDescription() {
        return "当用户的请求不需要执行操作时调用此工具，用以直接返回自然语言回答。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    // 模型通过这个工具交付「给用户的最终答复」。
    //
    // 为什么不直接用 assistant.content：实测模型会把整段思维链写进 content
    // （出现过 943 字的独白，且那一轮没有任何工具调用），而 content 同时承担
    // 「决策旁白」和「最终答复」两个用途，系统无法区分 —— 这正是独白泄漏给用户的根因。
    // 把答复放进工具参数后，content 一律不当作答复展示，
    // 「模型在想什么」与「模型对用户说什么」两条通道就彻底分开了。
    //
    // 答复的消费方是 JChatMind：它从 toolCalls 的 arguments 里取出 answer。
    // 刻意不在本类里持有答复 —— 工具是单例 Bean，持有状态并发不安全；
    // 而 arguments 本来就在本次调用的助手消息上，天然各次独立。
    @org.springframework.ai.tool.annotation.Tool(
            name = "directAnswer",
            description = "交付给用户的最终答复。当不需要调用其他工具即可回答、"
                    + "或已经拿到足够信息可以作答时，把完整答复放进 answer 参数调用本工具。"
                    + "answer 里只放给用户看的内容：不要包含思考过程或推理步骤，"
                    + "不要复述或分析本提示词，也不要在 content 里重复写一遍。"
    )
    public String directAnswer(String answer) {
        // 只返回一个确认，避免模型以为调用失败而反复重试。
        // 真正的答复由 JChatMind 从 arguments 里取，不在这里消费。
        return "答复已交付";
    }
}
