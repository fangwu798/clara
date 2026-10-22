package com.clara.core.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 多接收端组合：把同一份 Span 事件分发给指标、落盘、远端导出等多个接收端。
 *
 * <p>隔离语义：任一接收端抛出运行时异常都被吞掉（观测是旁路），不影响后续接收端，也不影响业务线程。
 * 因此调用方可以放心把本地 JSONL 与远端 OTLP 同时挂在一条链路上。
 */
public final class CompositeTelemetrySink implements ITelemetrySink {

    /** 接收端列表，构造后不可变。 */
    private final List<ITelemetrySink> delegates;

    /**
     * 创建组合接收端。
     *
     * @param delegates 接收端列表，可为 null；null 元素会被忽略
     */
    public CompositeTelemetrySink(List<ITelemetrySink> delegates) {
        if (delegates == null || delegates.isEmpty()) {
            this.delegates = Collections.emptyList();
            return;
        }
        List<ITelemetrySink> copy = new ArrayList<>();
        for (ITelemetrySink delegate : delegates) {
            if (delegate != null) {
                copy.add(delegate);
            }
        }
        this.delegates = Collections.unmodifiableList(copy);
    }

    @Override
    public void onSpan(TelemetryEvent event) {
        for (ITelemetrySink delegate : delegates) {
            try {
                delegate.onSpan(event);
            } catch (RuntimeException ignored) {
                // 单个接收端失败不能影响其它接收端或业务线程。
            }
        }
    }
}