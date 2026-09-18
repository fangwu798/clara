package com.clara.model.provider.compatible.handler;

import com.clara.kernel.ClaraError;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.StreamEvent;
import com.clara.model.api.StreamObserver;

/**
 * 按序号向观察者发射流事件，并保证终态事件只发一次。
 *
 * <p>归属说明（重要）：本类是 OpenAI 兼容流式对话模型的实现细节，只服务
 * {@code OpenAiCompatibleStreamingChatModel}，单独放在 handler 包中是为了让模型类只保留主流程，
 * <b>不是对外 API</b>，使用方不要直接依赖。
 *
 * <p>行为：
 * <ul>
 *   <li>事件序号从 1 开始，每次发射递增，用于让下游按序消费；</li>
 *   <li>{@code RESPONSE_COMPLETE} 与 {@code ERROR} 是终态事件，发射后发射器进入终态，后续调用一律忽略，
 *       保证下游只收到一个结束信号；</li>
 *   <li>观察者自身抛异常时直接把发射器置为终态，避免异常处理路径再次触发回调。</li>
 * </ul>
 */
public final class EventEmitter {
    /**
     * 事件接收方，由调用方提供。
     */
    private final StreamObserver observer;
    /**
     * 事件序号，从 1 开始递增。
     */
    private long sequence;
    /**
     * 是否已发出终态事件（完成或错误）。
     */
    private boolean terminal;

    /**
     * 创建发射器。
     *
     * @param observer 事件接收方
     */
    public EventEmitter(StreamObserver observer) {
        this.observer = observer;
    }

    /**
     * 发射一个流事件。
     *
     * @param type       事件类型
     * @param payload    事件负载
     * @param toolCallId 工具调用 id，可为 null
     * @param toolName   工具名，可为 null
     * @param response   完整响应，仅完成事件携带
     * @param error      错误信息，仅错误事件携带
     */
    public void emit(StreamEvent.Type type, String payload, String toolCallId, String toolName, ChatResponse response, ClaraError error) {
        if (terminal) {
            return;
        }
        if (type == StreamEvent.Type.RESPONSE_COMPLETE || type == StreamEvent.Type.ERROR) {
            terminal = true;
        }
        observer.onEvent(new StreamEvent(type, ++sequence, payload, toolCallId, toolName, response, error));
    }

    /**
     * 发射错误终态事件。
     *
     * @param error 错误信息
     */
    public void error(ClaraError error) {
        if (terminal) {
            return;
        }
        try {
            emit(StreamEvent.Type.ERROR, error.getCode(), null, null, null, error);
        } catch (RuntimeException ignored) {
            terminal = true;
        }
    }
}
