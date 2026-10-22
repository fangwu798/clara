package com.clara.core.telemetry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 Span 结束后的不可变事实记录，是链路追踪的唯一输出模型。
 *
 * <p>为什么用事件而不是直接写日志：控制台、JSONL 文件和未来的 OpenTelemetry 导出器都消费同一份数据，
 * 避免「控制台看到一套、文件里又是另一套」。事件不包含提示词正文、工具入参或密钥，只保存可安全外发的
 * 名称、耗时、状态和低敏属性。
 */
public final class TelemetryEvent {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String name;
    private final TelemetrySpanKindEnum kind;
    private final long startEpochMillis;
    private final long durationMillis;
    private final TelemetryStatusEnum status;
    private final String errorCategory;
    private final String errorCode;
    private final String errorMessage;
    private final Map<String, String> attributes;

    private TelemetryEvent(Builder builder) {
        this.traceId = builder.traceId;
        this.spanId = builder.spanId;
        this.parentSpanId = builder.parentSpanId;
        this.name = builder.name;
        this.kind = builder.kind;
        this.startEpochMillis = builder.startEpochMillis;
        this.durationMillis = builder.durationMillis;
        this.status = builder.status;
        this.errorCategory = builder.errorCategory;
        this.errorCode = builder.errorCode;
        this.errorMessage = builder.errorMessage;
        this.attributes = builder.attributes == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
    }

    /**
     * 创建事件构建器。
     *
     * @return 新的构建器，非 null
     */
    public static Builder builder() {
        return new Builder();
    }

    /** @return 同一条用户请求共享的链路标识，非 null */
    public String getTraceId() {
        return traceId;
    }

    /** @return 当前阶段标识，非 null */
    public String getSpanId() {
        return spanId;
    }

    /** @return 父阶段标识；根阶段为 null */
    public String getParentSpanId() {
        return parentSpanId;
    }

    /** @return 阶段名称，稳定且低基数，非 null */
    public String getName() {
        return name;
    }

    /** @return 阶段类别，非 null */
    public TelemetrySpanKindEnum getKind() {
        return kind;
    }

    /** @return 开始时间（epoch 毫秒） */
    public long getStartEpochMillis() {
        return startEpochMillis;
    }

    /** @return 耗时（毫秒），非负 */
    public long getDurationMillis() {
        return durationMillis;
    }

    /** @return 终态，非 null */
    public TelemetryStatusEnum getStatus() {
        return status;
    }

    /** @return 错误分类；成功时为 null */
    public String getErrorCategory() {
        return errorCategory;
    }

    /** @return 错误码；成功时为 null */
    public String getErrorCode() {
        return errorCode;
    }

    /** @return 脱敏后的错误文案；成功时为 null */
    public String getErrorMessage() {
        return errorMessage;
    }

    /** @return 只读属性表，非 null */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    /**
     * 事件构建器：字段较多，集中设置避免超长构造参数。
     */
    public static final class Builder {

        private String traceId;
        private String spanId;
        private String parentSpanId;
        private String name;
        private TelemetrySpanKindEnum kind = TelemetrySpanKindEnum.OTHER;
        private long startEpochMillis;
        private long durationMillis;
        private TelemetryStatusEnum status = TelemetryStatusEnum.OK;
        private String errorCategory;
        private String errorCode;
        private String errorMessage;
        private Map<String, String> attributes;

        private Builder() {
        }

        /** @param value 链路标识，不可为空白 @return 当前构建器 */
        public Builder traceId(String value) {
            this.traceId = value;
            return this;
        }

        /** @param value 阶段标识，不可为空白 @return 当前构建器 */
        public Builder spanId(String value) {
            this.spanId = value;
            return this;
        }

        /** @param value 父阶段标识，可为 null @return 当前构建器 */
        public Builder parentSpanId(String value) {
            this.parentSpanId = value;
            return this;
        }

        /** @param value 阶段名称，不可为空白 @return 当前构建器 */
        public Builder name(String value) {
            this.name = value;
            return this;
        }

        /** @param value 阶段类别，非 null @return 当前构建器 */
        public Builder kind(TelemetrySpanKindEnum value) {
            this.kind = value;
            return this;
        }

        /** @param value 开始时间（epoch 毫秒） @return 当前构建器 */
        public Builder startEpochMillis(long value) {
            this.startEpochMillis = value;
            return this;
        }

        /** @param value 耗时（毫秒） @return 当前构建器 */
        public Builder durationMillis(long value) {
            this.durationMillis = value;
            return this;
        }

        /** @param value 终态，非 null @return 当前构建器 */
        public Builder status(TelemetryStatusEnum value) {
            this.status = value;
            return this;
        }

        /** @param value 错误分类，可为 null @return 当前构建器 */
        public Builder errorCategory(String value) {
            this.errorCategory = value;
            return this;
        }

        /** @param value 错误码，可为 null @return 当前构建器 */
        public Builder errorCode(String value) {
            this.errorCode = value;
            return this;
        }

        /** @param value 脱敏错误文案，可为 null @return 当前构建器 */
        public Builder errorMessage(String value) {
            this.errorMessage = value;
            return this;
        }

        /** @param value 属性表，可为 null @return 当前构建器 */
        public Builder attributes(Map<String, String> value) {
            this.attributes = value;
            return this;
        }

        /**
         * 构建事件。
         *
         * @return 不可变事件，非 null
         * @throws IllegalArgumentException traceId / spanId / name 为空白时
         */
        public TelemetryEvent build() {
            if (traceId == null || traceId.trim().isEmpty()) {
                throw new IllegalArgumentException("traceId must not be blank");
            }
            if (spanId == null || spanId.trim().isEmpty()) {
                throw new IllegalArgumentException("spanId must not be blank");
            }
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            return new TelemetryEvent(this);
        }
    }
}
