package com.clara.model.provider.compatible.handler;

import com.clara.kernel.InvocationContext;
import com.clara.model.api.StreamHandle;
import java.util.concurrent.Future;

/**
 * 表示一次流式调用的句柄，用于查询完成状态与取消。
 *
 * <p>归属说明（重要）：本类是兼容适配器流式模型的实现细节，服务流式对话与流式生图两类模型，
 * 单独放在 handler 包中是为了让模型类只保留主流程，<b>不是对外 API</b>，使用方不要直接依赖。
 *
 * <p>使用顺序：模型提交任务后立即用 {@link #setFuture(Future)} 写入执行器返回的任务句柄，因此
 * {@link #isDone()} 在写入之前恒为 false；取消会同时取消调用令牌（通知读取线程停止）与中断底层任务，
 * 两者任一成功即视为取消成功。
 */
public final class AsyncStreamHandle implements StreamHandle {
    /**
     * 本次调用的上下文，取消时通过它的取消令牌通知读取线程。
     */
    private final InvocationContext context;
    /**
     * 执行器返回的任务句柄，提交后写入；使用 volatile 保证跨线程可见。
     */
    private volatile Future<?> future;

    /**
     * 创建句柄。
     *
     * @param context 本次调用的上下文
     */
    public AsyncStreamHandle(InvocationContext context) {
        this.context = context;
    }

    /**
     * 记录任务句柄，供取消与状态查询使用。
     *
     * @param value 任务句柄
     */
    public void setFuture(Future<?> value) {
        this.future = value;
    }

    /**
     * 取消本次流式调用。
     *
     * <p>同时取消调用令牌与中断任务线程，任一成功即返回 true；重复取消不会产生额外副作用。
     *
     * @return 是否成功触发取消
     */
    public boolean cancel() {
        boolean tokenCancelled = context.getCancellationToken().cancel();
        Future<?> current = future;
        boolean futureCancelled = current != null && current.cancel(true);
        return tokenCancelled || futureCancelled;
    }

    /**
     * 查询任务是否已结束。
     *
     * @return 任务已结束返回 true；尚未提交任务时返回 false
     */
    public boolean isDone() {
        Future<?> current = future;
        return current != null && current.isDone();
    }
}
