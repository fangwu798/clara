package com.clara.telemetry;

import com.clara.core.telemetry.ITelemetrySink;
import com.clara.core.telemetry.TelemetryEvent;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OTLP/HTTP Trace 导出器：批量、异步把 Span 发到 OpenTelemetry Collector。
 *
 * <p>为什么异步：导出网络抖动不能拖慢对话主链路。{@link #onSpan} 只做入队，队列满时丢弃并计数；
 * 后台守护线程按批发送，进程退出时 {@link #close()} 尽量把剩余事件刷完。
 *
 * <p>协议：{@code POST <endpoint>/v1/traces}，Content-Type 为 {@code application/json}，请求体由
 * {@link OtlpJsonBuilder} 生成。启用前先经过采样器，错误 / 慢请求不会被采样丢弃。
 */
public final class OtlpHttpTelemetrySink implements ITelemetrySink, Closeable {

    private static final Logger LOG = Logger.getLogger(OtlpHttpTelemetrySink.class.getName());
    private static final int QUEUE_CAPACITY = 4096;
    private static final int BATCH_SIZE = 64;
    private static final long CLOSE_JOIN_MILLIS = 2000L;

    private final TelemetryOtlpSettings settings;
    private final BlockingQueue<TelemetryEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong attempted = new AtomicLong();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final Object sendLock = new Object();
    private final Object flushMonitor = new Object();
    private final Thread worker;

    private OtlpHttpTelemetrySink(TelemetryOtlpSettings settings) {
        this.settings = settings;
        this.worker = new Thread(this::drainLoop, "clara-otel-export");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 按配置创建导出器。
     *
     * @param settings OTLP 配置，可为 null
     * @return 导出器；未启用或配置为空时返回 null
     */
    public static OtlpHttpTelemetrySink create(TelemetryOtlpSettings settings) {
        if (settings == null || !settings.isEnabled() || settings.getEndpoint().trim().isEmpty()) {
            return null;
        }
        return new OtlpHttpTelemetrySink(settings);
    }

    @Override
    public void onSpan(TelemetryEvent event) {
        if (event == null || !running.get()) {
            return;
        }
        if (queue.offer(event)) {
            accepted.incrementAndGet();
        } else {
            dropped.increment();
        }
    }

    /**
     * 等待调用前已提交的事件全部尝试发送完毕，主要供测试和关闭前兜底使用。
     *
     * <p>只排空队列并不够：后台线程可能已把事件取进本地批次并在等待下一次轮询。这里先唤醒后台线程立即
     * 发送本地批次，再等待「已提交数」与「已尝试数」对齐；超过配置超时后返回，避免网络故障拖住调用方。
     */
    public void flush() {
        long target = accepted.get();
        if (attempted.get() >= target) {
            return;
        }
        worker.interrupt();
        long deadline = System.currentTimeMillis() + Math.max(settings.getTimeoutMillis(), 1000L);
        synchronized (flushMonitor) {
            while (attempted.get() < target) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    flushMonitor.wait(remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        drainRemaining();
    }

    /**
     * 已丢弃的事件数（队列满）。
     *
     * @return 非负计数
     */
    public long droppedCount() {
        return dropped.sum();
    }

    /**
     * 导出失败次数（网络 / 非 2xx）。
     *
     * @return 非负计数
     */
    public long failureCount() {
        return failures.sum();
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        worker.interrupt();
        try {
            worker.join(CLOSE_JOIN_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        flush();
    }

    private void drainLoop() {
        List<TelemetryEvent> batch = new ArrayList<>();
        while (running.get() || !queue.isEmpty()) {
            try {
                TelemetryEvent event = queue.poll(settings.getFlushIntervalMillis(), TimeUnit.MILLISECONDS);
                if (event != null) {
                    batch.add(event);
                    queue.drainTo(batch, BATCH_SIZE - batch.size());
                }
                if (!batch.isEmpty() && (batch.size() >= BATCH_SIZE || event == null)) {
                    send(batch);
                    markAttempted(batch.size());
                    batch.clear();
                }
            } catch (InterruptedException exception) {
                if (!running.get()) {
                    break;
                }
                queue.drainTo(batch, BATCH_SIZE - batch.size());
                if (!batch.isEmpty()) {
                    send(batch);
                    markAttempted(batch.size());
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            send(batch);
            markAttempted(batch.size());
        }
    }

    private void drainRemaining() {
        List<TelemetryEvent> batch = new ArrayList<>();
        queue.drainTo(batch, BATCH_SIZE);
        while (!batch.isEmpty()) {
            send(batch);
            markAttempted(batch.size());
            batch.clear();
            queue.drainTo(batch, BATCH_SIZE);
        }
    }

    private void markAttempted(int count) {
        if (count <= 0) {
            return;
        }
        attempted.addAndGet(count);
        synchronized (flushMonitor) {
            flushMonitor.notifyAll();
        }
    }

    private void send(List<TelemetryEvent> batch) {
        if (batch.isEmpty()) {
            return;
        }
        synchronized (sendLock) {
            HttpURLConnection connection = null;
            try {
                String body = OtlpJsonBuilder.build(batch, settings.getServiceName());
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection = (HttpURLConnection) new URL(endpoint()).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(settings.getTimeoutMillis());
                connection.setReadTimeout(settings.getTimeoutMillis());
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                for (Map.Entry<String, String> header : settings.getHeaders().entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
                int status = connection.getResponseCode();
                consume(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
                if (status < 200 || status >= 300) {
                    failures.increment();
                    LOG.log(Level.WARNING, "OTLP export failed with HTTP " + status);
                }
            } catch (IOException | RuntimeException exception) {
                failures.increment();
                LOG.log(Level.FINE, "OTLP export failed: " + exception.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    private String endpoint() {
        String endpoint = settings.getEndpoint().trim();
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        return endpoint.endsWith("/v1/traces") ? endpoint : endpoint + "/v1/traces";
    }

    private static void consume(InputStream stream) {
        if (stream == null) {
            return;
        }
        try (InputStream input = stream) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) >= 0) {
                // 读取并丢弃响应体，保证连接可以复用。
            }
        } catch (IOException ignored) {
            // 响应体读取失败不影响本次导出结论。
        }
    }
}
