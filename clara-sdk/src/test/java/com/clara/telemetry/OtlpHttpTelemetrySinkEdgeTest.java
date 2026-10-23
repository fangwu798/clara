package com.clara.telemetry;

import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.core.telemetry.TelemetryStatusEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * OTLP 导出器的失败路径测试。
 *
 * <p>导出器必须「吞掉」网络错误而不是抛给调用方，同时把失败计入指标；
 * 另外 endpoint 末尾斜杠与已带 {@code /v1/traces} 的写法都要兼容。
 */
public class OtlpHttpTelemetrySinkEdgeTest {

    @Test
    public void createReturnsNullWhenDisabledOrMissing() {
        assertNull(OtlpHttpTelemetrySink.create(null));
        assertNull(OtlpHttpTelemetrySink.create(TelemetryOtlpSettings.defaults()));
    }

    @Test
    public void nonSuccessStatusCountsAsFailure() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            requests.incrementAndGet();
            byte[] body = "upstream down".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        OtlpHttpTelemetrySink sink = null;
        try {
            sink = OtlpHttpTelemetrySink.create(settings(server.getAddress().getPort(), ""));
            sink.onSpan(event());
            sink.flush();

            assertEquals(1, requests.get());
            assertEquals(1L, sink.failureCount());
            assertEquals(0L, sink.droppedCount());
        } finally {
            if (sink != null) {
                sink.close();
            }
            server.stop(0);
        }
    }

    @Test
    public void unreachableEndpointIsSwallowed() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        OtlpHttpTelemetrySink sink = OtlpHttpTelemetrySink.create(settings(port, ""));
        try {
            sink.onSpan(event());
            sink.flush();

            assertTrue(sink.failureCount() >= 1L);
        } finally {
            sink.close();
        }
    }

    @Test
    public void endpointWithTrailingSlashAndFullPathAreBothAccepted() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        OtlpHttpTelemetrySink first = OtlpHttpTelemetrySink.create(settings(port, "/"));
        OtlpHttpTelemetrySink second = OtlpHttpTelemetrySink.create(settings(port, "/v1/traces"));
        try {
            first.onSpan(event());
            first.flush();
            second.onSpan(event());
            second.flush();

            assertEquals(2, requests.get());
            assertEquals(0L, first.failureCount());
            assertEquals(0L, second.failureCount());
        } finally {
            first.close();
            second.close();
            server.stop(0);
        }
    }

    @Test
    public void ignoresNullAndPostCloseEvents() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        OtlpHttpTelemetrySink sink = OtlpHttpTelemetrySink.create(settings(server.getAddress().getPort(), ""));
        try {
            sink.onSpan(null);
            sink.flush();
            assertEquals(0L, sink.failureCount());

            sink.close();
            sink.close();
            sink.onSpan(event());
        } finally {
            server.stop(0);
        }
    }

    private static TelemetryOtlpSettings settings(int port, String pathSuffix) throws Exception {
        String yaml = "otlp:\n  enabled: true\n  endpoint: http://127.0.0.1:" + port + pathSuffix
                + "\n  timeoutMillis: 300\n  flushIntervalMillis: 50\n  serviceName: edge-test\n";
        return TelemetryOtlpSettings.load(new ObjectMapper(new YAMLFactory()).readTree(yaml));
    }

    private static TelemetryEvent event() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("provider", "DEEPSEEK");
        return TelemetryEvent.builder()
                .traceId("0123456789abcdef0123456789abcdef")
                .spanId("0123456789abcdef")
                .name("model.call")
                .kind(TelemetrySpanKindEnum.MODEL)
                .startEpochMillis(1000L)
                .durationMillis(10L)
                .status(TelemetryStatusEnum.OK)
                .attributes(attributes)
                .build();
    }
}