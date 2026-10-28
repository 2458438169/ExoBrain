package com.kama.jchatmind.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 请求节流：只在配置了最小间隔时生效，且有上界可控。 */
class ModelRequestPacerTest {

    @Test
    @DisplayName("默认不节流：0 间隔时连续请求不等待")
    void zeroIntervalDoesNotWait() {
        ModelRequestPacer pacer = new ModelRequestPacer(0);
        assertEquals(0, pacer.minIntervalMs());

        long started = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            pacer.await();
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(elapsedMs < 50, "0 间隔不应引入等待，实际 " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("配置间隔时，两次请求之间至少等待该间隔")
    void intervalIsEnforcedBetweenRequests() {
        ModelRequestPacer pacer = new ModelRequestPacer(80);
        pacer.await();
        long started = System.nanoTime();
        pacer.await();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(elapsedMs >= 70, "应至少等待约 80ms，实际 " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("负值按 0 处理，不当成无限等待")
    void negativeIntervalIsTreatedAsZero() {
        ModelRequestPacer pacer = new ModelRequestPacer(-5);
        assertEquals(0, pacer.minIntervalMs());
        long started = System.nanoTime();
        pacer.await();
        assertTrue((System.nanoTime() - started) / 1_000_000L < 50);
    }

    @Test
    @DisplayName("等待期间被中断时保留中断标记，不吞掉中断")
    void interruptionKeepsTheFlag() {
        ModelRequestPacer pacer = new ModelRequestPacer(5_000);
        pacer.await();
        Thread.currentThread().interrupt();
        pacer.await();
        assertTrue(Thread.interrupted(), "中断标记必须保留，供上层取消检查点尊重");
    }
}
