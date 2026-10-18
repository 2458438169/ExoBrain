package com.kama.jchatmind.runs;

/**
 * 提交阶段的明确拒绝（幂等键冲突、同会话已有活动运行、排队上限）。
 *
 * <p>由账号/游客入口分别映射为 HTTP 409 / 429，而不是静默排队或静默丢弃。
 * 提交失败意味着用户消息、运行记录与任务都没有写入（同一个事务回滚）。</p>
 */
public class RunSubmissionException extends RuntimeException {

    private final int status;
    private final String code;

    public RunSubmissionException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
