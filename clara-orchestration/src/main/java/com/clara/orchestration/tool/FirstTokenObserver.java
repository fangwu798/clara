package com.clara.orchestration.tool;

import com.clara.core.model.IStreamObserver;
import com.clara.dto.model.StreamEvent;
import com.clara.enums.StreamEventEnum;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 首字符计时观察者：转发流事件的同时记录第一个非空内容片段的到达时刻。
 *
 * <p>为什么单独一层：首字符耗时必须从「请求发出」到「第一个可展示片段」之间计算，这段横跨传输、解码与
 * 工具参数拼装；放在模型调用处计时会把连接建立也算进去，放在展示层又会被终端刷新干扰。这里只记录时间戳，
 * 由编排线程在流结束后读取并写入 Span，避免在读取线程上并发修改链路属性。
 *
 * <p>计时的片段：正文、推理内容与工具参数分片。纯工具调用轮没有正文，工具参数就是模型的第一份有效输出；
 * 空负载片段不计时，否则部分平台会先发一个空 delta，把首字符耗时压到失真。
 */
public final class FirstTokenObserver implements IStreamObserver {

    /** 下游观察者，可为 null。 */
    private final IStreamObserver delegate;

    /** 首个有效片段到达的纳秒时间戳；0 表示尚未到达。 */
    private final AtomicLong firstTokenNanos = new AtomicLong(0L);

    /**
     * 创建首字符计时观察者。
     *
     * @param delegate 下游观察者，可为 null（只计时，不展示）
     */
    public FirstTokenObserver(IStreamObserver delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onEvent(StreamEvent event) {
        if (event != null && isContentDelta(event.getType()) && hasText(event.getPayload())) {
            firstTokenNanos.compareAndSet(0L, System.nanoTime());
        }
        if (delegate != null) {
            delegate.onEvent(event);
        }
    }

    /**
     * 计算首字符耗时。
     *
     * @param startedNanos 请求发出前的纳秒时间戳
     * @return 首字符耗时毫秒；尚未收到内容时为 null
     */
    public Long firstTokenMillis(long startedNanos) {
        long first = firstTokenNanos.get();
        if (first <= 0L) {
            return null;
        }
        return Math.max(0L, (first - startedNanos) / 1_000_000L);
    }

    private static boolean isContentDelta(StreamEventEnum type) {
        return type == StreamEventEnum.TEXT_DELTA
                || type == StreamEventEnum.REASONING_DELTA
                || type == StreamEventEnum.TOOL_ARGUMENT_DELTA;
    }

    private static boolean hasText(String payload) {
        return payload != null && !payload.isEmpty();
    }
}