package com.kama.jchatmind.execution;

import com.kama.jchatmind.auth.AuthException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 只做计时的工具包装：委托给真正的回调，记录每次调用的开始/结束与安全的结果分类。
 *
 * <p>Spring AI 1.1 的 {@code ToolContext} 不携带 toolCallId，因此这里只采集“第几次调用、什么工具、
 * 真实耗时”。执行循环在 {@code ToolCallingManager} 返回后按顺序把样本与本次模型请求的
 * {@code toolCallId} 对齐；对不上的样本不虚构 ID。</p>
 */
public final class TimedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolTimingCollector collector;

    public TimedToolCallback(ToolCallback delegate, ToolTimingCollector collector) {
        this.delegate = delegate;
        this.collector = collector;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return timed(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return timed(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String timed(String arguments, ToolCall call) {
        String name = null;
        try {
            name = getToolDefinition().name();
        } catch (RuntimeException ignored) {
            // 工具定义不可读时仍然执行，只是样本没有名字（不伪造名称）。
        }
        long started = System.nanoTime();
        try {
            String result = call.invoke();
            collector.add(new ToolTimingCollector.Sample(name, arguments, started,
                    millisSince(started), RunEventStatus.SUCCEEDED, null));
            return result;
        } catch (RuntimeException | Error failure) {
            collector.add(new ToolTimingCollector.Sample(name, arguments, started,
                    millisSince(started), RunEventStatus.FAILED, classify(failure)));
            throw failure;
        }
    }

    private static long millisSince(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static String classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AuthException) {
                return "AUTHORIZATION_REVOKED";
            }
        }
        return "TOOL_FAILED";
    }

    @FunctionalInterface
    private interface ToolCall {
        String invoke();
    }
}