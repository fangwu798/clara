package com.clara.core.telemetry;

import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个进行中的链路阶段；关闭时把事件交给 {@link Telemetry} 的接收端。
 *
 * <p>使用方式（必须用 try-with-resources，保证异常路径也会结束 Span）：
 * <pre>
 * try (TelemetrySpan span = telemetry.startSpan("model.call", TelemetrySpanKindEnum.MODEL)) {
 *     span.attribute("provider", "DEEPSEEK");
 *     HttpResponse response = invoke();
 *     span.attribute("input_tokens", response.getInputTokens());
 * }
 * </pre>
 *
 * <p>线程约束：Span 与 {@link Telemetry} 的当前上下文绑定在同一线程。跨线程执行（线程池、异步回调）时，
 * 父 Span 不会自动传播；应在异步边界显式记录父子关系，或把任务收敛到同一线程。
 */
public final class TelemetrySpan implements AutoCloseable {

    private static final int MAX_ATTRIBUTE_LENGTH = 512;
    private static final int MAX_MESSAGE_LENGTH = 240;

    private static final TelemetrySpan NOOP = new TelemetrySpan(null, "noop", TelemetrySpanKindEnum.OTHER, null);

    private final Telemetry telemetry;
    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String name;
    private final TelemetrySpanKindEnum kind;
    private final TelemetrySpan parent;
    private final long startNanos;
    private final long startEpochMillis;
    private final Map<String, String> attributes = new LinkedHashMap<>();

    private TelemetryStatusEnum status = TelemetryStatusEnum.OK;
    private String errorCategory;
    private String errorCode;
    private String errorMessage;
    private boolean closed;

    TelemetrySpan(Telemetry telemetry, String traceId, String spanId, String parentSpanId,
            String name, TelemetrySpanKindEnum kind, TelemetrySpan parent) {
        this.telemetry = telemetry;
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.name = name;
        this.kind = kind;
        this.parent = parent;
        this.startNanos = System.nanoTime();
        this.startEpochMillis = System.currentTimeMillis();
    }

    private TelemetrySpan(Telemetry telemetry, String name, TelemetrySpanKindEnum kind, TelemetrySpan parent) {
        this(telemetry, "noop", "noop", null, name, kind, parent);
    }

    /**
     * 无接收端时使用的空 Span；所有写入操作都无副作用。
     *
     * @return 共享的空 Span，非 null
     */
    static TelemetrySpan noop() {
        return NOOP;
    }

    /**
     * 设置一个标量属性。null 值会被忽略，过长文本会截断，避免把大段正文写进链路。
     *
     * @param key   属性名，非空白
     * @param value 属性值，可为 null
     * @return 当前 Span，便于链式调用
     */
    public TelemetrySpan attribute(String key, Object value) {
        if (telemetry == null || key == null || key.trim().isEmpty() || value == null) {
            return this;
        }
        String text = sanitize(String.valueOf(value), MAX_ATTRIBUTE_LENGTH);
        attributes.put(key.trim(), text);
        return this;
    }

    /**
     * 显式设置终态。
     *
     * @param value 终态，非 null
     * @return 当前 Span
     */
    public TelemetrySpan status(TelemetryStatusEnum value) {
        if (telemetry != null && value != null) {
            this.status = value;
        }
        return this;
    }

    /**
     * 记录领域错误：同时写入分类、错误码和脱敏文案。
     *
     * @param error 领域错误，可为 null
     * @return 当前 Span
     */
    public TelemetrySpan error(ClaraError error) {
        if (telemetry == null || error == null) {
            return this;
        }
        this.status = TelemetryStatusEnum.ERROR;
        this.errorCategory = error.getCategory() == null ? null : error.getCategory().name();
        this.errorCode = error.getCode();
        this.errorMessage = sanitize(error.getSafeMessage(), MAX_MESSAGE_LENGTH);
        return this;
    }

    /**
     * 记录任意异常；{@link ClaraException} 会复用其结构化错误字段。
     *
     * @param failure 异常，可为 null
     * @return 当前 Span
     */
    public TelemetrySpan error(Throwable failure) {
        if (telemetry == null || failure == null) {
            return this;
        }
        if (failure instanceof ClaraException) {
            return error(((ClaraException) failure).getError());
        }
        this.status = TelemetryStatusEnum.ERROR;
        this.errorCode = failure.getClass().getSimpleName();
        this.errorMessage = sanitize(failure.getMessage(), MAX_MESSAGE_LENGTH);
        return this;
    }

    /**
     * 标记为已取消。
     *
     * @return 当前 Span
     */
    public TelemetrySpan cancelled() {
        if (telemetry != null) {
            this.status = TelemetryStatusEnum.CANCELLED;
        }
        return this;
    }

    /**
     * 结束 Span 并把事件交给接收端。重复调用无副作用；观测侧异常不会向业务抛出。
     */
    @Override
    public void close() {
        if (telemetry == null || closed) {
            return;
        }
        closed = true;
        long duration = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        telemetry.finish(this, duration);
    }

    String traceId() {
        return traceId;
    }

    String spanId() {
        return spanId;
    }

    String parentSpanId() {
        return parentSpanId;
    }

    String name() {
        return name;
    }

    TelemetrySpanKindEnum kind() {
        return kind;
    }

    TelemetrySpan parent() {
        return parent;
    }

    long startEpochMillis() {
        return startEpochMillis;
    }

    TelemetryStatusEnum status() {
        return status;
    }

    String errorCategory() {
        return errorCategory;
    }

    String errorCode() {
        return errorCode;
    }

    String errorMessage() {
        return errorMessage;
    }

    Map<String, String> attributes() {
        return attributes;
    }

    /**
     * 去掉换行并脱敏常见凭据，避免观测数据变成第二个泄漏面。
     */
    private static String sanitize(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replace('\r', ' ').replace('\n', ' ').trim();
        cleaned = cleaned.replaceAll("(?i)(bearer\\s+)[^\\s]+", "$1***");
        cleaned = cleaned.replaceAll("sk-[A-Za-z0-9_-]+", "sk-***");
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength) + "...";
    }
}
