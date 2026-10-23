package com.clara.telemetry;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Prometheus 抓取端点配置：绑定地址与端口。
 *
 * <p>默认关闭且只绑定回环地址。若 Prometheus 跑在 Docker 中，需要把 host 改为 {@code 0.0.0.0}，
 * 并在宿主机防火墙 / Docker 网络层面限制访问。
 */
public final class TelemetryPrometheusSettings {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 9464;

    private final boolean enabled;
    private final String host;
    private final int port;

    private TelemetryPrometheusSettings(boolean enabled, String host, int port) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
    }

    static TelemetryPrometheusSettings load(JsonNode telemetryNode) {
        if (telemetryNode == null || !telemetryNode.isObject()) {
            return defaults();
        }
        JsonNode node = telemetryNode.get("prometheus");
        if (node == null || !node.isObject()) {
            return defaults();
        }
        return new TelemetryPrometheusSettings(bool(node, "enabled", false),
                text(node, "host", DEFAULT_HOST), port(node, "port", DEFAULT_PORT));
    }

    static TelemetryPrometheusSettings defaults() {
        return new TelemetryPrometheusSettings(false, DEFAULT_HOST, DEFAULT_PORT);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
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

    private static int port(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return fallback;
        }
        int port = value.asInt();
        return port > 0 && port <= 65535 ? port : fallback;
    }
}