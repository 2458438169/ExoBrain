package com.kama.jchatmind.runs;

import com.kama.jchatmind.model.entity.Agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 助手配置哈希。
 *
 * <p><b>必须与任务 05 的 {@code RunLifecycleService.configHash(Agent)} 完全一致</b>（同样的字段与顺序、
 * 同样的 {@code \0} 分隔符、同样的 SHA-256 十六进制小写）。05 在运行开始时写入
 * {@code exobrain_run.agent_config_hash}；07 在提交（入队）时写同一个值，在领取时重新计算并比较，
 * 从而发现“排队期间助手配置被改成了别的模型/工具/知识库”，而不是静默换一个配置继续执行。</p>
 *
 * <p>05 的方法是包内可见的静态方法，07 不能修改 {@code execution/**}，因此这里保留一份逐字段相同的实现；
 * 集成测试用真实数据库里的同一份配置比较两个哈希（必须相等）。</p>
 */
public final class RunConfigHash {

    private RunConfigHash() {
    }

    public static String of(Agent agent) {
        if (agent == null) {
            return null;
        }
        return hash(String.join("\u0000",
                nullToEmpty(agent.getName()),
                nullToEmpty(agent.getDescription()),
                nullToEmpty(agent.getSystemPrompt()),
                nullToEmpty(agent.getModel()),
                nullToEmpty(agent.getAllowedTools()),
                nullToEmpty(agent.getAllowedKbs()),
                nullToEmpty(agent.getChatOptions())));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String hash(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
