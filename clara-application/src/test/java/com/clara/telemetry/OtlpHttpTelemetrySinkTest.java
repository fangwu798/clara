package com.clara.telemetry;

import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.core.telemetry.TelemetryStatusEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

/**
 * OTLP 导出测试：用本地 HTTP 端点捕获请求体，验证协议字段与批量发送。
 */
public class OtlpHttpTelemetrySinkTest {

    @Test
    public void exportsOtlpJson() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            captured.set(read(exchange.getRequestBody()));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            JsonNode node = new ObjectMapper(new YAMLFactory()).readTree(
                    "otlp:\n  enabled: true\n  endpoint: http://127.0.0.1:" + port
                            + "\n  serviceName: clara-test\n");
            TelemetryOtlpSettings settings = TelemetryOtlpSettings.load(node);
            OtlpHttpTelemetrySink sink = OtlpHttpTelemetrySink.create(settings);
            try {
                sink.onSpan(event());
                sink.flush();

                String body = captured.get();
                assertTrue(body != null && body.contains("\"resourceSpans\""));
                assertTrue(body.contains("clara-test"));
                assertTrue(body.contains("0123456789abcdef0123456789abcdef"));
                assertTrue(body.contains("0123456789abcdef"));
                assertTrue(body.contains("model.call"));
                assertTrue(body.contains("\"first_token_ms\""));
                assertTrue(body.contains("\"startTimeUnixNano\":\"1000000000\""));
                assertTrue(body.contains("\"intValue\":\"120\""));
            } finally {
                sink.close();
            }
        } finally {
            server.stop(0);
        }
    }

    private static TelemetryEvent event() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("provider", "DEEPSEEK");
        attributes.put("model", "deepseek-v4-pro");
        attributes.put("first_token_ms", "120");
        return TelemetryEvent.builder()
                .traceId("0123456789abcdef0123456789abcdef")
                .spanId("0123456789abcdef")
                .name("model.call")
                .kind(TelemetrySpanKindEnum.MODEL)
                .startEpochMillis(1000L)
                .durationMillis(200L)
                .status(TelemetryStatusEnum.OK)
                .attributes(attributes)
                .build();
    }

    private static String read(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}