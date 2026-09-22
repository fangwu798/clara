package com.clara.context;

import com.clara.kernel.exception.ClaraException;
import com.clara.kernel.exception.ClaraError;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消令牌：用一个线程安全的标志位协调一次调用（或一个会话内全部调用）的协作式取消。
 *
 * <p>是什么、在哪一层：本类属于内核的上下文包（{@code com.clara.context}），是不含业务语义的小对象；
 * 它由 {@link ConvContext} 持有，并被该会话创建的每个 {@link CallContext} 复用——同一个会话内的调用共享
 * 同一个令牌，因此一次 {@link ConvContext#cancel()} 就能让本会话内所有进行中与后续的调用在下一个检查点终止。
 *
 * <p>谁创建谁调用：由 {@link ConvContext} 在构造会话时创建（也可显式传入外部令牌）；置位方是
 * {@link ConvContext#cancel()} 与流句柄 {@code StreamHandle#cancel()}，检查方是传输层与 SSE 解析器（调
 * {@link #throwIfCancellationRequested(String)}）以及 {@link ConvContext#throwIfCancelled()}。
 *
 * <p>使用方法：
 * <ol>
 *   <li>取消方调 {@link #cancel()} 置位，幂等；</li>
 *   <li>执行方在循环与关键点调 {@link #throwIfCancellationRequested(String)}，已置位则立即抛取消异常；</li>
 *   <li>只想读状态、不想抛异常时用 {@link #isCancellationRequested()}。</li>
 * </ol>
 *
 * <p>生命周期与线程安全：状态是单个 {@link AtomicBoolean}，可跨线程共享；只能由未置位变为已置位，没有重置
 * 方法，需要可恢复的取消语义时应新建实例。
 *
 * <p>失败语义：只有 {@link #throwIfCancellationRequested(String)} 会抛异常（已置位时抛
 * {@link ClaraException}，错误来自 {@link ClaraError#cancelled(String)}，属取消分类、不可重试）；其余方法
 * 不抛异常。
 */
public final class CancellationToken {

    /**
     * 取消标志；只能由 false 变为 true，置位后不再改变。
     */
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /**
     * 请求取消。
     *
     * @return 本次调用是否真正改变了状态（首次置位返回 {@code true}，此前已置位返回 {@code false}）
     */
    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    /**
     * 是否已被请求取消。
     *
     * @return 已置位时返回 {@code true}
     */
    public boolean isCancellationRequested() {
        return cancelled.get();
    }

    /**
     * 已请求取消时立即抛出取消异常，供循环与关键点做快速失败。
     *
     * @param traceId 归因用的跟踪标识，可为 null（调用方通常传当前调用的 {@code traceId}）
     * @throws ClaraException 已置位时（取消类错误码、分类为取消，不可重试）
     */
    public void throwIfCancellationRequested(String traceId) {
        if (cancelled.get()) {
            throw new ClaraException(cancelledError(traceId));
        }
    }

    /**
     * 构造取消领域错误。
     *
     * @param traceId 归因用的跟踪标识，可为 null
     * @return 取消类领域错误，非 null
     */
    private ClaraError cancelledError(String traceId) {
        return ClaraError.cancelled(traceId);
    }
}
