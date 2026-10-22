package com.clara.telemetry;

import com.clara.core.telemetry.CompositeTelemetrySink;
import com.clara.core.telemetry.ITelemetrySink;
import com.clara.core.telemetry.Telemetry;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 可观测运行时：把指标、采样、JSONL 落盘、OTLP 导出与 Prometheus 端点装配成一个可关闭对象。
 *
 * <p>装配顺序很重要：指标接收端位于采样器之前，保证 token / 错误计数全量；采样器之后才是本地存储与
 * OTLP 导出，成功链路可按比例丢弃，错误与慢请求始终保留。
 *
 * <p>关闭顺序：先停 HTTP 端点，再冲刷采样缓冲，最后关闭导出器与文件写入器。任一步失败都不向业务抛出。
 */
public final class TelemetryRuntime implements Closeable {

    private final Telemetry telemetry;
    private final TelemetryStore store;
    private final TelemetryMetrics metrics;
    private final SamplingTelemetrySink sampler;
    private final OtlpHttpTelemetrySink otlp;
    private final TelemetryHttpServer httpServer;

    private TelemetryRuntime(Telemetry telemetry, TelemetryStore store, TelemetryMetrics metrics,
            SamplingTelemetrySink sampler, OtlpHttpTelemetrySink otlp, TelemetryHttpServer httpServer) {
        this.telemetry = telemetry;
        this.store = store;
        this.metrics = metrics;
        this.sampler = sampler;
        this.otlp = otlp;
        this.httpServer = httpServer;
    }

    /**
     * 按配置启动可观测运行时。
     *
     * @param settings      配置，不可为 null
     * @param workspaceRoot 工作目录，用于放置 {@code .clara/telemetry}
     * @return 运行时，非 null；关闭 {@code telemetry.enabled} 时返回空实现
     */
    public static TelemetryRuntime start(TelemetrySettings settings, Path workspaceRoot) {
        if (settings == null || !settings.isEnabled()) {
            return new TelemetryRuntime(Telemetry.noop(), null, null, null, null, null);
        }
        TelemetryMetrics metrics = new TelemetryMetrics();
        TelemetryStore store = new TelemetryStore(workspaceRoot, settings.getMaxRecentSpans(),
                settings.getRetainDays());
        OtlpHttpTelemetrySink otlp = OtlpHttpTelemetrySink.create(settings.getOtlp());
        List<ITelemetrySink> traceSinks = new ArrayList<>();
        traceSinks.add(store);
        if (otlp != null) {
            traceSinks.add(otlp);
        }
        SamplingTelemetrySink sampler = new SamplingTelemetrySink(settings.getSampleRate(),
                settings.getSlowTraceMillis(), traceSinks);
        List<ITelemetrySink> sinks = new ArrayList<>();
        sinks.add(metrics);
        sinks.add(sampler);
        Telemetry telemetry = new Telemetry(new CompositeTelemetrySink(sinks));
        TelemetryHttpServer httpServer = TelemetryHttpServer.start(settings.getPrometheus(), metrics);
        return new TelemetryRuntime(telemetry, store, metrics, sampler, otlp, httpServer);
    }

    /** @return 链路追踪入口，非 null */
    public Telemetry telemetry() {
        return telemetry;
    }

    /** @return 本地链路存储；未启用时为 null */
    public TelemetryStore store() {
        return store;
    }

    /** @return 指标聚合器；未启用时为 null */
    public TelemetryMetrics metrics() {
        return metrics;
    }

    /** @return Prometheus 端点地址；未启用时为 null */
    public String metricsAddress() {
        return httpServer == null ? null : httpServer.address();
    }

    @Override
    public void close() {
        closeQuietly(httpServer);
        closeQuietly(sampler);
        closeQuietly(otlp);
        closeQuietly(store);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 关闭是收尾动作：任一接收端关闭失败不能阻断其余资源释放。
        }
    }
}