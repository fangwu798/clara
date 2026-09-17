package com.clara.model.provider.http;

import com.clara.model.provider.http.event.ProviderSseEvent;

/**
 * 监听服务提供商 SSE 的生命周期事件。
 *
 * <p>用途：作为 {@link ProviderHttpTransport} 流式调用的回调，让适配器在响应体尚未读完时就能
 * 逐条消费事件（如文本增量、推理增量、工具调用参数增量），而不必等整个响应结束。
 *
 * <p>使用规范：
 * <ul>
 *   <li>回调在读取 SSE 的线程上同步触发，实现中不要长时间阻塞，否则会拖慢事件读取。</li>
 *   <li>返回 {@code true} 表示继续接收后续事件；返回 {@code false} 表示提前结束流，
 *       传输层会停止读取并正常返回，适用于已收到完成标记的场景。</li>
 *   <li>抛出异常会中断整次调用并向上抛出为协议错误，因此事件校验失败应通过异常表达，不要静默忽略。</li>
 *   <li>本接口只传递原始事件，事件到领域对象的聚合由实现自行完成；实现需注意线程可见性与幂等性。</li>
 * </ul>
 */
public interface ProviderSseListener {
    /**
     * 处理一个解析完成的 SSE 事件。
     *
     * @param event 已解析的 SSE 事件，包含事件名、数据以及可选的 id 与重试间隔
     * @return {@code true} 继续接收后续事件；{@code false} 提前结束流
     */
    boolean onEvent(ProviderSseEvent event);
}
