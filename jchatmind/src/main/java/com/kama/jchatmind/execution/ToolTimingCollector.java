package com.kama.jchatmind.execution;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具调用的真实计时样本。每个 ToolCallback 调用各产生一条样本（按实际调用顺序），
 * 由执行循环随后与本次模型的 toolCall 列表对齐，绝不把整轮耗时平均分给多个工具。
 */
public final class ToolTimingCollector {

    public record Sample(String toolName, String arguments, long startedNanos, long durationMs,
                         RunEventStatus status, String errorCode) {
    }

    private final List<Sample> samples = new ArrayList<>();

    public void add(Sample sample) {
        synchronized (samples) {
            samples.add(sample);
        }
    }

    public List<Sample> drain() {
        synchronized (samples) {
            List<Sample> copy = List.copyOf(samples);
            samples.clear();
            return copy;
        }
    }

    public int size() {
        synchronized (samples) {
            return samples.size();
        }
    }
}