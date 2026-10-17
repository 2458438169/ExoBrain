package com.kama.jchatmind.execution;

/**
 * 事件持久化端口。{@link RunRecorder} 只依赖这个接口，因此执行循环可以在没有任何数据库的
 * 单元测试里记录同一批事件；生产实现见 {@link JdbcRunRepository} 与 {@code RunLifecycleService}。
 */
public interface RunEventSink {

    void append(RunEventRecord event);

    void touch(String runId);

    void setAnswerMessageId(String runId, String answerMessageId);

    /** 测试与“无追踪”运行使用的空实现。 */
    RunEventSink NONE = new RunEventSink() {
        @Override
        public void append(RunEventRecord event) {
        }

        @Override
        public void touch(String runId) {
        }

        @Override
        public void setAnswerMessageId(String runId, String answerMessageId) {
        }
    };
}