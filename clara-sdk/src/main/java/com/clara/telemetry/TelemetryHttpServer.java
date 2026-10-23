package com.clara.telemetry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Prometheus 抓取端点：暴露 {@code /metrics} 与 {@code /healthz}。
 *
 * <p>为什么用 JDK 内置 HttpServer：指标端点只需要两个只读路由，引入 Web 框架会把可观测依赖带进主程序。
 * 默认只绑定回环地址，需要被 Docker 内 Prometheus 抓取时显式改为 {@code 0.0.0.0}。
 *
 * <p>生命周期：随会话 / 进程启动，随关闭而停止；端口占用等错误只记日志，不阻断对话启动。
 */
public final class TelemetryHttpServer implements Closeable {

    private static final Logger LOG = Logger.getLogger(TelemetryHttpServer.class.getName());
    private static final String METRICS_PATH = "/metrics";
    private static final String HEALTH_PATH = "/healthz";

    private final HttpServer server;
    private final ExecutorService executor;

    private TelemetryHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /**
     * 按配置启动端点。
     *
     * @param settings 配置，可为 null
     * @param metrics  指标聚合器，可为 null
     * @return 已启动的端点；未启用 / 指标为空 / 端口不可用时返回 null
     */
    public static TelemetryHttpServer start(TelemetryPrometheusSettings settings, TelemetryMetrics metrics) {
        if (settings == null || !settings.isEnabled() || metrics == null) {
            return null;
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(settings.getHost(), settings.getPort()), 0);
            ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "clara-metrics-http");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext(METRICS_PATH, exchange -> respond(exchange, 200,
                    "text/plain; version=0.0.4; charset=utf-8", metrics.prometheus()));
            server.createContext(HEALTH_PATH, exchange -> respond(exchange, 200,
                    "text/plain; charset=utf-8", "ok\n"));
            server.start();
            return new TelemetryHttpServer(server, executor);
        } catch (IOException | RuntimeException exception) {
            LOG.log(Level.WARNING, "Prometheus endpoint unavailable: " + exception.getMessage());
            return null;
        }
    }

    /**
     * 端点地址，供启动提示展示。
     *
     * @return 形如 {@code 127.0.0.1:9464/metrics} 的地址
     */
    public String address() {
        return server.getAddress().getHostString() + ":" + server.getAddress().getPort() + METRICS_PATH;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}