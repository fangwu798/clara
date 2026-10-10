package com.clara.kernel.stream;

import com.clara.dto.model.StreamEvent;

/**
 * 领域流事件的接收方。
 *
 * <p>位置：契约层（{@code com.clara.kernel.stream}）。流式链路把解码后的领域事件回调给它，
 * 调用方据此即时展示增量、或在终态事件上取回完整响应。
 *
 * <p>与 {@link com.clara.kernel.api.execute.ISseListener} 的分工：那个接口接收的是传输层解析出的<b>原始
 * SSE 事件</b>（按事件名与 data 分派，属于协议细节）；本接口接收的是<b>领域事件</b>
 * （{@link StreamEvent}，已经映射成文本增量、推理增量、工具调用与终态），业务代码只依赖本接口。
 *
 * <p>实现约定：回调在读取线程上按序发生，实现应尽快返回，不要阻塞；抛出 {@link RuntimeException} 会中断
 * 本次流并按错误事件处理。本接口是函数式接口，可直接用 lambda 实现。
 */
@FunctionalInterface
public interface IStreamObserver {

    /**
     * 处理一个领域流事件。
     *
     * @param event 流事件，非 null；{@code RESPONSE_COMPLETE} 与 {@code ERROR} 为终态，只会出现一次
     */
    void onEvent(StreamEvent event);
}
