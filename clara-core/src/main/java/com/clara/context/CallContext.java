package com.clara.context;

import lombok.Getter;

/**
 * 承载一次具体调用的跟踪标识、截止时间、取消信号与可选的日志事件接收方。
 *
 * <p>是什么、在哪一层：本类属于内核的上下文包（{@code com.clara.context}），是调用级上下文值对象，
 * 不是模型 API 契约；它随一次调用（而不是一个会话）从调用方向下贯穿到 HTTP 层，让每一层都能读到同一份
 * 跟踪标识、截止时间、取消信号，并让日志事件能实时回流给调用方。
 *
 * <p>谁创建谁调用：由最终发起调用的业务代码创建（通常是宿主应用的 Agent / CLI），随后放进请求对象
 * （如 {@code ChatRequest}）随调用链向下传递；HTTP 层（{@code ModelInvoker}、日志发射器、SSE 解析）
 * 只读取，不修改。
 *
 * <p>使用方法（典型序列）：
 * <ol>
 *   <li>每次调用新建一个，{@code traceId} 每次不同，便于在日志与观测中区分单次请求：</li>
 *   <li>{@code CallContext call = new CallContext(traceId, deadlineEpochMillis, token);} 不关心日志时
 *       使用三参构造器；</li>
 *   <li>需要实时接收日志事件时改用四参构造器并传入日志观察者（
 *       {@code new CallContext(traceId, deadlineEpochMillis, token, logObserver)}），日志事件以
 *       {@code StreamEvent(LOG, …)} 的形式回调该观察者；不传表示日志只走 SLF4J；</li>
 *   <li>把它交给请求对象，长耗时操作在循环中同时检查 {@link #remainingMillis(long)} 与取消令牌。</li>
 * </ol>
 *
 * <p>生命周期与线程安全：实例不可变（字段全部 final），可安全跨线程传递与长期持有；无资源需要释放。
 * 日志观察者由调用方提供，其线程安全性由实现方保证——流式调用会在读取线程上回调它。
 *
 * <p>失败语义：跟踪标识为空白、取消令牌为 null 时构造抛 {@link IllegalArgumentException}，属于调用方
 * 编程错误，不可重试；本类不校验截止时间是否已过期（过期只在被检查时体现为剩余时间为零），也不校验
 * 日志观察者是否为 null（null 表示不接收日志事件）。
 */
@Getter
public final class CallContext {
    /**
     * 端到端观测使用的跟踪标识；必填，每次调用重新生成。
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
