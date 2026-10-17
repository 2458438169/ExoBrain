package com.kama.jchatmind.execution;

import java.util.EnumSet;
import java.util.Set;

/**
 * 一次对话运行的终态机（共享契约 §5）。
 *
 * <p>05 只做观测：不负责领取/租约，因此只有“已开始执行”的运行会进入 {@code RUNNING}。
 * 终态（{@code SUCCEEDED / FAILED / CANCELLED / INTERRUPTED}）一旦写定，迟到的进度更新不能把它改回
 * {@code RUNNING} 或另一个终态。数据库层用同样的条件更新做兜底（见 {@link JdbcRunRepository}）。</p>
 */
public enum RunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    private static final Set<RunStatus> FROM_QUEUED = EnumSet.of(RUNNING, CANCELLED, INTERRUPTED);
    private static final Set<RunStatus> FROM_RUNNING = EnumSet.of(SUCCEEDED, FAILED, CANCELLED, INTERRUPTED);

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
    }

    /** 同一个终态重复写入是幂等的；任何终态都不能迁移到别的状态。 */
    public boolean canTransitionTo(RunStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        if (isTerminal()) {
            return false;
        }
        return switch (this) {
            case QUEUED -> FROM_QUEUED.contains(target);
            case RUNNING -> FROM_RUNNING.contains(target);
            default -> false;
        };
    }

    public static RunStatus parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("run status is required");
        }
        return valueOf(value);
    }
}
