package com.clara.telemetry;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OTLP/HTTP 导出配置：是否启用、Collector 地址、鉴权头、超时与服务名。
 *
 * <p>默认关闭。开启后 Span 仍会先经过采样器，错误与慢请求始终导出。
 */
public final class TelemetryOtlpSettings {

    private static final String DEFAULT_ENDPOINT = "http://localhost:4318";
    private static final String DEFAULT_SERVICE_NAME = "clara";
    private static final int DEFAULT_TIMEOUT_MILLIS = 5000;
    private static final int DEFAULT_FLUSH_INTERVAL_MILLIS = 1000;

    private final boolean enabled;
    private final String endpoint;
    private final Map<String, String> headers;
    private final int timeoutMillis;
    private final String serviceName;
    private final int flushIntervalMillis;

    private TelemetryOtlpSettings(boolean enabled, String endpoint, Map<String, String> headers,
            int timeoutMillis, String serviceName, int flushIntervalMillis) {
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.timeoutMillis = timeoutMillis;
        this.serviceName = serviceName;
        this.flushIntervalMillis = flushIntervalMillis;
    }

    static TelemetryOtlpSettings load(JsonNode telemetryNode) {
        if (telemetryNode == null || !telemetryNode.isObject()) {
            return defaults();
        }
        JsonNode node = telemetryNode.get("otlp");
        if (node == null || !node.isObject()) {
            return defaults();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode headerNode = node.get("headers");
        if (headerNode != null && headerNode.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = headerNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue() != null && field.getValue().isValueNode()) {
                    headers.put(field.getKey(), field.getValue().asText());
                }
            }
        }
        return new TelemetryOtlpSettings(
                bool(node, "enabled", false),
                text(node, "endpoint", DEFAULT_ENDPOINT),
                headers,
                positiveInt(node, "timeoutMillis", DEFAULT_TIMEOUT_MILLIS),
                text(node, "serviceName", DEFAULT_SERVICE_NAME),
                positiveInt(node, "flushIntervalMillis", DEFAULT_FLUSH_INTERVAL_MILLIS));
    }

    static TelemetryOtlpSettings defaults() {
        return new TelemetryOtlpSettings(false, DEFAULT_ENDPOINT, Collections.emptyMap(),
                DEFAULT_TIMEOUT_MILLIS, DEFAULT_SERVICE_NAME, DEFAULT_FLUSH_INTERVAL_MILLIS);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public String getServiceName() {
        return serviceName;
    }

    public int getFlushIntervalMillis() {
        return flushIntervalMillis;
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().trim().isEmpty()
                ? value.asText().trim() : fallback;
    }

    private static int positiveInt(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() && value.asInt() > 0 ? value.asInt() : fallback;
    }
}