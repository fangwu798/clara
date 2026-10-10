package com.clara.kernel.api.execute;

import com.clara.kernel.http.execute.sse.SseEvent;

/**
 * 监听服务提供商 SSE 的生命周期事件（传输层接口）。
 *
 * <p>是什么、在哪一层：传输层 SPI（{@code com.clara.kernel.api.execute}），由 {@code Jdk8HttpTransport}
 * 在流式调用时回调。它接收的是解析后的
 * <b>原始 SSE 事件</b>（事件名、data、id、retry），因此不要把业务逻辑写在这里——领域事件由流式链路映射为
 * {@link com.clara.dto.model.StreamEvent} 后交给
 * {@link com.clara.kernel.stream.IStreamObserver}。
 */
public interface ISseListener {

    /**
     * 处理一个解析完成的 SSE 事件。
     *
     * @param event 已解析的 SSE 事件，包含事件名、数据以及可选的 id 与重试间隔，非 null
     * @return {@code true} 继续接收后续事件；{@code false} 提前结束流（传输层停止读取并正常返回）
     */
    boolean onEvent(SseEvent event);
}
