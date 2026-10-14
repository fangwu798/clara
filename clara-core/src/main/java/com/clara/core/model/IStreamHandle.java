package com.clara.core.model;

/**
 * 流式调用句柄：查询状态、取消本次流、取回累积完成的响应。
 *
 * <p>位置：契约层（{@code com.clara.kernel.stream}）。由流式链路在提交调用后立即返回，调用方拿它做三件事：
 * 判断是否结束、请求取消、在终态之后取回完整响应对象。
 *
 * <p>使用方法：
 * <ol>
 *   <li>把 IStreamObserver交给流式入口，入口返回本句柄；</li>
 *   <li>轮询 {@link #isDone()} 或等待终态事件，结束（无论成功或失败）后本句柄不再变化；</li>
 *   <li>需要中断时调 {@link #cancel()}，正在进行的读取会在下一个取消检查点终止；</li>
 *   <li>成功结束后用 {@link #result()} 取累积响应；失败或未结束时返回 null。</li>
 * </ol>
 *
 * <p>线程安全：实现需保证 {@link #cancel()} 与 {@link #isDone()} 可被其他线程调用。
 */
public interface IStreamHandle {

    /**
     * 取消本次流。
     *
     * @return 本次调用是否真正触发了取消；已结束或此前已取消时返回 false
     */
    boolean cancel();

    /**
     * 查询本次流是否已经结束。
     *
     * @return 终态事件（完成或错误）已发生、或已取消时返回 true，否则 false
     */
    boolean isDone();

    /**
     * 取回累积完成的响应对象。
     *
     * @return 响应对象；流未结束、已取消或失败时返回 null
     */
    IResponse result();
}
