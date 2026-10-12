package com.clara.orchestration.tool;

import com.clara.core.model.IResponse;
import com.clara.dto.model.StreamEvent;
import com.clara.dto.tool.ToolCall;
import com.clara.kernel.stream.IStreamObserver;
import com.clara.model.stream.RecordingObserver;

import java.util.List;

/**
 * 流式工具调用拼装观察者：把每一轮流事件转发给宿主观察者（用于实时展示），同时按 {@code index} 把
 * {@code TOOL_ARGUMENT_DELTA} 分片拼装为完整工具调用。
 *
 * <p>是什么、在哪一层：clara-orchestration 的工具层装饰器（包 {@code com.clara.orchestration.tool}），
 * 由编排器在「宿主希望流式展示」时包一层再交给模型调用。它本身不做展示，展示由宿主观察者负责。
 *
 * <p>为什么需要：OpenAI 兼容协议在流式下把工具调用拆成分片下发，响应对象不会自动累加工具调用；
 * 若不拼装，编排层会读不到工具调用，从而把工具轮误判为最终答复。这里复用
 * {@link RecordingObserver} 的拼装逻辑，在流结束后把结果交给编排层补齐到响应上。
 *
 * <p>线程安全：{@code onEvent} 在流的读取线程上回调，{@link #toolCalls()} 在流结束后由编排线程读取；
 * 读取发生在句柄进入终态之后（此时增量事件已全部回调完成），因此是安全的。
 */
public final class StreamingToolCallObserver implements IStreamObserver {

    private final RecordingObserver<IResponse> recorder = new RecordingObserver<IResponse>(IResponse.class);

    private final IStreamObserver delegate;

    /**
     * 创建拼装观察者。
     *
     * @param delegate 宿主观察者，用于实时展示；可为 null（此时只做拼装）
     */
    public StreamingToolCallObserver(IStreamObserver delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onEvent(StreamEvent event) {
        recorder.onEvent(event);
        if (delegate != null) {
            delegate.onEvent(event);
        }
    }

    /**
     * 取拼装完成的工具调用列表。
     *
     * @return 工具调用列表，非 null；本轮无工具调用时为空列表
     */
    public List<ToolCall> toolCalls() {
        return recorder.toolCalls();
    }
}
