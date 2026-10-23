package com.clara.telemetry;

import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.core.telemetry.TelemetryStatusEnum;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * 把 {@link TelemetryEvent} 转成 OTLP/HTTP JSON（{@code resourceSpans} 结构）。
 *
 * <p>只做协议映射：traceId / spanId 补齐到 OTel 要求的 32 / 16 位；SpanKind 按阶段性质映射为
 * INTERNAL 或 CLIENT；状态映射为 UNSET / OK / ERROR。指标不走本类，由 Prometheus 端点直接抓取。
 */
final class OtlpJsonBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TRACE_ID_LENGTH = 32;
    private static final int SPAN_ID_LENGTH = 16;

    private OtlpJsonBuilder() {
    }

    /**
     * 构建一次 OTLP 导出请求体。
     *
     * @param events      待导出的 Span 事件，非 null
     * @param serviceName 服务名，非空白
     * @return JSON 文本，非 null
     * @throws JsonProcessingException 序列化失败时
     */
    static String build(List<TelemetryEvent> events, String serviceName) throws JsonProcessingException {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode resourceSpans = root.putArray("resourceSpans");
        ObjectNode resourceSpan = resourceSpans.addObject();
        ObjectNode resource = resourceSpan.putObject("resource");
        ArrayNode resourceAttributes = resource.putArray("attributes");
        resourceAttributes.add(attribute("service.name", serviceName));
        ArrayNode scopeSpans = resourceSpan.putArray("scopeSpans");
        ObjectNode scopeSpan = scopeSpans.addObject();
        scopeSpan.putObject("scope").put("name", "clara").put("version", "1.0");
        ArrayNode spans = scopeSpan.putArray("spans");
        for (TelemetryEvent event : events) {
            spans.add(span(event));
        }
        return JSON.writeValueAsString(root);
    }

    private static ObjectNode span(TelemetryEvent event) {
        ObjectNode span = JSON.createObjectNode();
        span.put("traceId", normalize(event.getTraceId(), TRACE_ID_LENGTH));
        span.put("spanId", normalize(event.getSpanId(), SPAN_ID_LENGTH));
        if (event.getParentSpanId() != null && !event.getParentSpanId().trim().isEmpty()) {
            span.put("parentSpanId", normalize(event.getParentSpanId(), SPAN_ID_LENGTH));
        }
        span.put("name", event.getName());
        span.put("kind", kind(event.getKind()));
        span.put("startTimeUnixNano", Long.toString(event.getStartEpochMillis() * 1_000_000L));
        span.put("endTimeUnixNano", Long.toString((event.getStartEpochMillis() + event.getDurationMillis()) * 1_000_000L));
        ArrayNode attributes = span.putArray("attributes");
        for (Map.Entry<String, String> entry : event.getAttributes().entrySet()) {
            attributes.add(attribute(entry.getKey(), entry.getValue()));
        }
        ObjectNode status = span.putObject("status");
        status.put("code", statusCode(event.getStatus()));
        if (event.getErrorMessage() != null && !event.getErrorMessage().trim().isEmpty()) {
            status.put("message", event.getErrorMessage());
        }
        return span;
    }

    private static ObjectNode attribute(String key, String value) {
        ObjectNode attribute = JSON.createObjectNode();
        attribute.put("key", key);
        ObjectNode typed = attribute.putObject("value");
        if (value != null && ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) {
            typed.put("boolValue", Boolean.parseBoolean(value));
            return attribute;
        }
        Long number = number(value);
        if (number != null) {
            typed.put("intValue", Long.toString(number));
        } else {
            typed.put("stringValue", value == null ? "" : value);
        }
        return attribute;
    }

    private static Long number(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int kind(TelemetrySpanKindEnum kind) {
        if (kind == TelemetrySpanKindEnum.MODEL || kind == TelemetrySpanKindEnum.TOOL) {
            return 3;
        }
        return 1;
    }

    private static int statusCode(TelemetryStatusEnum status) {
        if (status == TelemetryStatusEnum.ERROR) {
            return 2;
        }
        if (status == TelemetryStatusEnum.OK) {
            return 1;
        }
        return 0;
    }

    private static String normalize(String value, int length) {
        String text = value == null ? "" : value.replace("-", "");
        if (text.length() > length) {
            return text.substring(0, length);
        }
        StringBuilder padded = new StringBuilder();
        for (int index = text.length(); index < length; index++) {
            padded.append('0');
        }
        return padded.append(text).toString();
    }
}