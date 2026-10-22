package com.clara.core.telemetry;

/**
 * 显式链路上下文：把 traceId 与父 spanId 作为值对象传递，供跨线程或并行分支创建子 Span。
 *
 * <p>为什么需要：{@link Telemetry} 默认用 ThreadLocal 维护当前 Span，工作流并行分支、异步流式回调等
 * 场景切换线程后拿不到父上下文。调用方在提交任务前取一次上下文，再在目标线程用它创建子 Span，
 * 就能保持同一条 trace，而不是产生互不相关的新链路。
 *
 * <p>不可变值对象，可安全跨线程传递。
 */
public final class TelemetryContext {

    /** 链路标识，32 位十六进制。 */
    private final String traceId;

    /** 父 Span 标识，16 位十六进制。 */
    private final String spanId;

    /**
     * 创建链路上下文。
     *
     * @param traceId 链路标识，不可为空白
     * @param spanId  父 Span 标识，不可为空白
     * @throws IllegalArgumentException 任一参数为空白时
     */
    public TelemetryContext(String traceId, String spanId) {
        if (traceId == null || traceId.trim().isEmpty()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        if (spanId == null || spanId.trim().isEmpty()) {
            throw new IllegalArgumentException("spanId must not be blank");
        }
        this.traceId = traceId.trim();
        this.spanId = spanId.trim();
    }

    /** @return 链路标识，非 null */
    public String getTraceId() {
        return traceId;
    }

    /** @return 父 Span 标识，非 null */
    public String getSpanId() {
        return spanId;
    }

    @Override
    public String toString() {
        return traceId + "/" + spanId;
    }
}