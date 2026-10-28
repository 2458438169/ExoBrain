package com.kama.jchatmind.models;

/**
 * 模型请求节奏闸门：只负责「下一次真实请求还要等多久」，不负责等待本身。
 *
 * <p>把「算间隔」和「等待」分开，是因为等待必须能被打断、并且在等待前后各查一次运行状态
 * （取消、租约失效、权限变化）。等待放在这里会让 {@link ModelRuntimeService} 无法感知运行态，
 * 出现在取消之后仍然发起模型调用的问题。</p>
 */
public interface ModelRequestGate {

    /** 不节流：默认实现，行为与未引入节流前一致。 */
    ModelRequestGate NONE = () -> 0L;

    /**
     * 预约下一个请求时隙。
     *
     * @return 需要等待的纳秒数；0 表示可以立即发送
     */
    long reserveDelayNanos();
}
