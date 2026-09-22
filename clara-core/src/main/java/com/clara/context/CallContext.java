package com.clara.context;

import lombok.Getter;

/**
 * 调用上下文：承载一次具体调用的跟踪标识、截止时间与取消信号。
 *
 * <p>是什么、在哪一层：本类属于内核的上下文包（{@code com.clara.context}），是调用级上下文值对象，
 * 不是模型 API 契约；它随一次调用（而不是一个会话）向下贯穿到传输层的读取循环与 SSE 解析，让每一层都能读到
 * 同一份跟踪标识、截止时间与取消信号。
 *
 * <p>谁创建谁调用：由 {@link ConvContext#newCall(long)} 在传输层真正发出请求时创建
 * （见 {@code com.clara.kernel.http.execute.Jdk8HttpTransport}），随后作为参数传给读取循环与
 * SSE 解析器（{@code SseParser}）；这些层只读取，不修改。
 *
 * <p>使用方法（典型序列）：
 * <ol>
 *   <li>一般不需要自行构造：传输层调 {@link ConvContext#newCall(long)}，由它生成新的 {@code traceId} 并按
 *       「当前时间 + 超时」算出截止时间，同时登记进会话调用历史；</li>
 *   <li>需要在链路之外自造时用唯一的三参构造器
 *       {@code new CallContext(traceId, deadlineEpochMillis, cancellationToken)}；</li>
 *   <li>长耗时操作在循环中同时看两个信号：{@link #remainingMillis(long)} 换算出的剩余时间与
 *       {@code getCancellationToken()} 的取消状态，任一触发即中断本次调用。</li>
 * </ol>
 *
 * <p>生命周期与线程安全：实例不可变（字段全部 final），可安全跨线程传递与长期持有；无资源需要释放。
 * 取消令牌的线程安全由 {@link CancellationToken} 自身保证。
 *
 * <p>失败语义：跟踪标识为空白、取消令牌为 null 时构造抛 {@link IllegalArgumentException}，属于调用方
 * 编程错误，不可重试；本类不校验截止时间是否已过期（过期只在被检查时体现为剩余时间为零）。
 */
@Getter
public final class CallContext {
    /**
     * 端到端观测使用的跟踪标识；必填、不可为空白，由 {@link ConvContext#newCall(long)} 每次调用重新生成。
     */
    private final String traceId;
    /**
     * 本次调用的绝对截止时间，单位为 Unix epoch 毫秒；当前时间达到或超过该值时剩余时间为零。
     */
    private final long deadlineEpochMillis;
    /**
     * 本次调用的协作式取消令牌。
     */
    private final CancellationToken cancellationToken;




    /**
     * 创建调用上下文。
     *
     * @param traceId             跟踪标识，不可为空白
     * @param deadlineEpochMillis 绝对截止时间，单位为 Unix epoch 毫秒
     * @param cancellationToken   协作式取消令牌，不可为 null
     * @throws IllegalArgumentException 跟踪标识为空白或取消令牌为 null 时
     */
    public CallContext(String traceId, long deadlineEpochMillis, CancellationToken cancellationToken) {
        if (traceId == null || traceId.trim().isEmpty()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        if (cancellationToken == null) {
            throw new IllegalArgumentException("cancellationToken must not be null");
        }
        this.traceId = traceId;
        this.deadlineEpochMillis = deadlineEpochMillis;
        this.cancellationToken = cancellationToken;
    }

    /**
     * 计算指定时刻距离截止时间的剩余毫秒数，已到期时返回零。
     *
     * @param now 当前 Unix epoch 毫秒时间
     * @return 非负的剩余毫秒数
     */
    public long remainingMillis(long now) {
        return Math.max(0L, deadlineEpochMillis - now);
    }
}
