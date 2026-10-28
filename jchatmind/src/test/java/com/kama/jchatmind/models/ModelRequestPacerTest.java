package com.kama.jchatmind.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节奏闸门只负责算「还要等多久」，不负责等待本身。
 *
 * <p>等待必须由运行循环执行，才能在被取消或中断时停下；这里只验证时隙预约的算术与边界，
 * 不验证取消语义（那部分在 {@code RunTracingExecutionTest} 里用假模型验证「委托调用为 0」）。</p>
 */
class ModelRequestPacerTest {

    @Test
    @DisplayName("默认不节流：0 间隔时不需要等待")
    void zeroIntervalNeverWaits() {
        ModelRequestPacer pacer = new ModelRequestPacer(0);
        assertEquals(0, pacer.minIntervalMs());
        for (int i = 0; i < 5; i++) {
            assertEquals(0L, pacer.reserveDelayNanos(), "0 间隔不应产生等待");
        }
    }

    @Test
    @DisplayName("配置间隔时，第二次预约需要等待接近该间隔")
    void intervalIsReservedBetweenRequests() {
        ModelRequestPacer pacer = new ModelRequestPacer(500);
        pacer.reserveDelayNanos();
        long delayNanos = pacer.reserveDelayNanos();
        long delayMs = delayNanos / 1_000_000L;
        assertTrue(delayMs >= 450 && delayMs <= 550, "第二次应约等待 500ms，实际 " + delayMs + "ms");
    }

    @Test
    @DisplayName("连续预约按间隔累加，不会因为不等待而丢掉节奏")
    void reservationsAccumulate() {
        ModelRequestPacer pacer = new ModelRequestPacer(200);
        pacer.reserveDelayNanos();
        long first = pacer.reserveDelayNanos();
        long second = pacer.reserveDelayNanos();
        assertTrue(second >= first, "第三次预约应至少和第二次一样长：" + first + " / " + second);
        assertTrue(second / 1_000_000L >= 300, "实际 " + second / 1_000_000L + "ms");
    }

    @Test
    @DisplayName("负值按 0 处理，不当成无限等待")
    void negativeIntervalIsTreatedAsZero() {
        ModelRequestPacer pacer = new ModelRequestPacer(-5);
        assertEquals(0, pacer.minIntervalMs());
        assertEquals(0L, pacer.reserveDelayNanos());
    }
}
