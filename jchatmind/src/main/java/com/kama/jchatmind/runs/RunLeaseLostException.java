package com.kama.jchatmind.runs;

/** 租约已丢失：任务被恢复流程标记中断、或续租失败。迟到的 worker 不得再写任何结果。 */
public class RunLeaseLostException extends RuntimeException {
    public RunLeaseLostException(String message) {
        super(message);
    }
}
