package com.kama.jchatmind.runs;

/**
 * 运行中收到取消请求（协作式停止）。
 *
 * <p>只能在检查点抛出：已经发出的模型调用与工具调用（可能包含外部副作用）不会因此被撤回，
 * 界面与文档不得声称已经回滚。检查点之后不再发起新的工具调用。</p>
 */
public class RunCancelledException extends RuntimeException {
    public RunCancelledException(String message) {
        super(message);
    }
}
