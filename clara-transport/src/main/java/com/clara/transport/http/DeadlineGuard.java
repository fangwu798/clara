package com.clara.transport.http;

import com.clara.core.transport.IExecutionGuard;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于绝对截止时间的执行守卫，可被其他线程取消。
 */
public final class DeadlineGuard implements IExecutionGuard {

    private final long deadlineEpochMillis;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private DeadlineGuard(long deadlineEpochMillis) {
        this.deadlineEpochMillis = deadlineEpochMillis;
    }

    /**
     * 创建在指定毫秒后到期的守卫。
     *
     * @param timeoutMillis 超时毫秒数，必须为正
     * @return 守卫，非 null
     */
    public static DeadlineGuard after(long timeoutMillis) {
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        long now = System.currentTimeMillis();
        long deadline = timeoutMillis > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + timeoutMillis;
        return new DeadlineGuard(deadline);
    }

    /**
     * 创建无截止时间、仅支持主动取消的守卫。
     *
     * @return 守卫，非 null
     */
    public static DeadlineGuard withoutDeadline() {
        return new DeadlineGuard(Long.MAX_VALUE);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public long remainingMillis() {
        if (deadlineEpochMillis == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, deadlineEpochMillis - System.currentTimeMillis());
    }

    /**
     * 请求取消。
     */
    public void cancel() {
        cancelled.set(true);
    }
}
