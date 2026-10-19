package com.clara.core.transport;

import com.clara.exception.HttpTransportException;

/**
 * 执行守卫：为一次 HTTP 调用提供协作式取消与截止时间检查。
 *
 * <p>模型侧由 {@code CallContext} 适配，MCP 侧由每次请求的超时时间构造；
 * 传输实现会在发送前与读取过程中调用 {@link #check()}。
 */
public interface IExecutionGuard {

    /**
     * 无取消、无截止时间的守卫。
     */
    IExecutionGuard NONE = new IExecutionGuard() {
        public boolean isCancelled() {
            return false;
        }

        public long remainingMillis() {
            return Long.MAX_VALUE;
        }
    };

    /**
     * 是否已请求取消。
     *
     * @return 已取消返回 true
     */
    boolean isCancelled();

    /**
     * 距离截止时间的剩余毫秒数。
     *
     * @return 非负毫秒数；无截止时间时返回 {@link Long#MAX_VALUE}
     */
    long remainingMillis();

    /**
     * 检查取消与截止时间，失败时抛出传输异常。
     *
     * @throws HttpTransportException 已取消或已超时时
     */
    default void check() {
        if (isCancelled()) {
            throw HttpTransportException.cancelled();
        }
        if (remainingMillis() <= 0L) {
            throw HttpTransportException.timeout();
        }
    }
}
