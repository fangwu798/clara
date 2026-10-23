package com.clara.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 链路追踪与导出开关：{@code config/clara.yml} 的 {@code telemetry} 节点。
 *
 * <p>配置形态（节点缺省即可）：
 * <pre>
 * telemetry:
 *   enabled: true              # 是否记录 Span / 聚合指标
 *   maxRecentSpans: 500        # 内存中保留的最近 Span 数，供 /trace 回看
 *   retainDays: 7              # 磁盘 JSONL 保留天数，0 表示不清理
 *   sampleRate: 1.0            # 成功链路保留比例；错误 / 慢请求始终保留
 *   slowTraceMillis: 10000     # 慢请求阈值；0 表示关闭按耗时保留
 *   otlp:
 *     enabled: false
 *     endpoint: http://localhost:4318
 *     headers: {}
 *     timeoutMillis: 5000
 *     serviceName: clara
 *     flushIntervalMillis: 1000
 *   prometheus:
 *     enabled: false
 *     host: 127.0.0.1
 *     port: 9464
 * </pre>
 *
 * <p>任何字段缺失或非法都按默认值处理，观测配置不应阻断启动。
 */
@Getter
public final class TelemetrySettings {

    private static final String NODE = "telemetry";
    private static final int DEFAULT_MAX_RECENT = 500;
    private static final int DEFAULT_RETAIN_DAYS = 7;
    private static final double DEFAULT_SAMPLE_RATE = 1.0d;
    private static final int DEFAULT_SLOW_TRACE_MILLIS = 10_000;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** 是否启用链路记录与指标聚合。 */
    private final boolean enabled;
    /** 内存保留条数。 */
    private final int maxRecentSpans;
    /** 磁盘保留天数。 */
    private final int retainDays;
    /** 成功链路保留比例，0~1。 */
    private final double sampleRate;
    /** 慢请求阈值毫秒；0 表示关闭。 */
    private final int slowTraceMillis;
    /** OTLP 导出配置。 */
    private final TelemetryOtlpSettings otlp;
    /** Prometheus 抓取端点配置。 */
    private final TelemetryPrometheusSettings prometheus;

    private TelemetrySettings(boolean enabled, int maxRecentSpans, int retainDays, double sampleRate,
            int slowTraceMillis, TelemetryOtlpSettings otlp, TelemetryPrometheusSettings prometheus) {
        this.enabled = enabled;
        this.maxRecentSpans = maxRecentSpans;
        this.retainDays = retainDays;
        this.sampleRate = sampleRate;
        this.slowTraceMillis = slowTraceMillis;
        this.otlp = otlp;
        this.prometheus = prometheus;
    }

    /**
     * 读取配置；文件缺失或读取失败时返回默认值。
     *
     * @param configFile 本地配置文件，可为 null
     * @return 配置值对象，非 null
     */
    public static TelemetrySettings load(Path configFile) {
        if (configFile == null || !Files.isReadable(configFile)) {
            return defaults();
        }
        JsonNode node;
        try {
            node = YAML.readTree(Files.readAllBytes(configFile)).get(NODE);
        } catch (IOException | RuntimeException failure) {
            return defaults();
        }
        if (node == null || !node.isObject()) {
            return defaults();
        }
        return new TelemetrySettings(bool(node, "enabled", true),
                positiveInt(node, "maxRecentSpans", DEFAULT_MAX_RECENT),
                nonNegativeInt(node, "retainDays", DEFAULT_RETAIN_DAYS),
                sampleRate(node),
                nonNegativeInt(node, "slowTraceMillis", DEFAULT_SLOW_TRACE_MILLIS),
                TelemetryOtlpSettings.load(node),
                TelemetryPrometheusSettings.load(node));
    }

    /**
     * 默认配置：开启记录、全量保留、不导出。
     *
     * @return 默认配置，非 null
     */
    public static TelemetrySettings defaults() {
        return new TelemetrySettings(true, DEFAULT_MAX_RECENT, DEFAULT_RETAIN_DAYS, DEFAULT_SAMPLE_RATE,
                DEFAULT_SLOW_TRACE_MILLIS, TelemetryOtlpSettings.defaults(),
                TelemetryPrometheusSettings.defaults());
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    private static int positiveInt(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() && value.asInt() > 0 ? value.asInt() : fallback;
    }

    private static int nonNegativeInt(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() && value.asInt() >= 0 ? value.asInt() : fallback;
    }

    private static double sampleRate(JsonNode node) {
        JsonNode value = node.get("sampleRate");
        if (value == null || !value.isNumber()) {
            return DEFAULT_SAMPLE_RATE;
        }
        double rate = value.asDouble();
        if (Double.isNaN(rate) || rate < 0.0d || rate > 1.0d) {
            return DEFAULT_SAMPLE_RATE;
        }
        return rate;
    }
}