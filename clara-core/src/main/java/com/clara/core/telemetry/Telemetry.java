package com.clara.core.telemetry;

import java.util.UUID;

/**
 * 链路追踪入口：创建 Span、维护当前线程的父 Span，并在结束时把事件交给 {@link ITelemetrySink}。
 *
 * <p>是什么、在哪一层：core 的观测契约实现，不依赖任何具体后端。应用层传入 JSONL、控制台或
 * OpenTelemetry 接收端即可，业务模块只依赖本类。
 *
 * <p>根 Span：一次用户请求的第一层 Span；子 Span 通过当前线程自动挂到父 Span 上。若接收端为 null，
 * 返回共享空 Span，调用方无需写 if 判断。
 */
public final class Telemetry {

    private static final Telemetry NOOP = new Telemetry(null);

    /** OTel 规范：traceId 为 32 位十六进制。 */
    private static final int TRACE_ID_LENGTH = 32;

    /** OTel 规范：spanId 为 16 位十六进制。 */
    private static final int SPAN_ID_LENGTH = 16;

    private final ITelemetrySink sink;
    private final ThreadLocal<TelemetrySpan> current = new ThreadLocal<>();

    /**
     * 创建追踪入口。
     *
     * @param sink 事件接收端；为 null 时退化为空实现
     */
    public Telemetry(ITelemetrySink sink) {
        this.sink = sink;
    }

    /**
     * 空实现：不产生任何事件。
     *
     * @return 共享空实例，非 null
     */
    public static Telemetry noop() {
        return NOOP;
    }

    /**
     * 是否会产生事件。
     *
     * @return 接收端存在时返回 true
     */
    public boolean isEnabled() {
        return sink != null;
    }

    /**
     * 创建并启动一个 Span；父 Span 取当前线程正在执行的 Span。
     *
     * @param name 阶段名称，非空白
     * @param kind 阶段类别，非 null
     * @return 进行中的 Span，非 null；必须关闭
     * @throws IllegalArgumentException name 为空白时
     */
    public TelemetrySpan startSpan(String name, TelemetrySpanKindEnum kind) {
        if (sink == null) {
            return TelemetrySpan.noop();
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("span name must not be blank");
        }
        TelemetrySpan parent = current.get();
        String traceId = parent == null ? newId(TRACE_ID_LENGTH) : parent.traceId();
        String parentSpanId = parent == null ? null : parent.spanId();
        TelemetrySpan span = new TelemetrySpan(this, traceId, newId(SPAN_ID_LENGTH), parentSpanId, name.trim(),
                kind == null ? TelemetrySpanKindEnum.OTHER : kind, parent);
        current.set(span);
        return span;
    }

    /**
     * 用显式上下文创建 Span：用于工作流并行分支、异步回调等已切换线程的场景。
     *
     * <p>{@code context} 为 null 时等价于创建新的根链路；否则沿用其 traceId，并把 context 的 spanId
     * 作为父 Span。Span 关闭后恢复当前线程原有的父级，避免并行分支执行完毕后污染调用线程的上下文。
     *
     * @param name    阶段名称，非空白
     * @param kind    阶段类别，可为 null（按 OTHER 处理）
     * @param context 父链路上下文，可为 null
     * @return 进行中的 Span，非 null；必须关闭
     */
    public TelemetrySpan startSpan(String name, TelemetrySpanKindEnum kind, TelemetryContext context) {
        if (sink == null) {
            return TelemetrySpan.noop();
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("span name must not be blank");
        }
        String traceId = context == null ? newId(TRACE_ID_LENGTH) : context.getTraceId();
        String parentSpanId = context == null ? null : context.getSpanId();
        TelemetrySpan restoreParent = context == null ? null : current.get();
        TelemetrySpan span = new TelemetrySpan(this, traceId, newId(SPAN_ID_LENGTH), parentSpanId, name.trim(),
                kind == null ? TelemetrySpanKindEnum.OTHER : kind, restoreParent);
        current.set(span);
        return span;
    }

    /**
     * 取当前线程正在执行的 Span。
     *
     * @return 当前 Span；不在任何 Span 内时为 null
     */
    public TelemetrySpan currentSpan() {
        return current.get();
    }

    /**
     * 取当前链路的 traceId。
     *
     * @return 当前 traceId；不在任何 Span 内时为 null
     */
    public String currentTraceId() {
        TelemetrySpan span = current.get();
        return span == null ? null : span.traceId();
    }

    /**
     * 取当前线程的链路上下文，供跨线程创建子 Span。
     *
     * @return 当前上下文；不在任何 Span 内时为 null
     */
    public TelemetryContext currentContext() {
        TelemetrySpan span = current.get();
        return span == null ? null : new TelemetryContext(span.traceId(), span.spanId());
    }

    /**
     * 结束 Span：先恢复父上下文，再交给接收端，保证接收端异常不会破坏调用栈。
     *
     * @param span     已结束的 Span，非 null
     * @param duration 耗时毫秒，非负
     */
    void finish(TelemetrySpan span, long duration) {
        if (current.get() == span) {
            if (span.parent() == null) {
                current.remove();
            } else {
                current.set(span.parent());
            }
        }
        if (sink == null) {
            return;
        }
        TelemetryEvent event = TelemetryEvent.builder()
                .traceId(span.traceId())
                .spanId(span.spanId())
                .parentSpanId(span.parentSpanId())
                .name(span.name())
                .kind(span.kind())
                .startEpochMillis(span.startEpochMillis())
                .durationMillis(duration)
                .status(span.status())
                .errorCategory(span.errorCategory())
                .errorCode(span.errorCode())
                .errorMessage(span.errorMessage())
                .attributes(span.attributes())
                .build();
        try {
            sink.onSpan(event);
        } catch (RuntimeException ignored) {
            // 观测是旁路：接收端失败不能影响本轮对话。
        }
    }

    private static String newId(int length) {
        String value = UUID.randomUUID().toString().replace("-", "");
        return value.length() <= length ? value : value.substring(0, length);
    }
}
