package com.clara.telemetry;

import com.clara.core.telemetry.TelemetrySpan;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Assembly tests for the telemetry runtime and Prometheus endpoint. Disabled mode must be side-effect
 * free; enabled mode must persist spans and expose a scrape address.
 */
public class TelemetryRuntimeAndHttpTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void disabledRuntimeReturnsNoopFacilities() throws Exception {
        TelemetryRuntime runtime = TelemetryRuntime.start(null, folder.newFolder("disabled").toPath());
        try {
            assertNull(runtime.store());
            assertNull(runtime.metrics());
            assertNull(runtime.metricsAddress());
            TelemetrySpan span = runtime.telemetry().startSpan("noop", TelemetrySpanKindEnum.OTHER);
            span.close();
        } finally {
            runtime.close();
            runtime.close();
        }
    }

    @Test
    public void disabledByConfigurationSkipsStoreAndEndpoint() throws Exception {
        Path root = folder.newFolder("workspace").toPath();
        Path config = writeConfig("telemetry:\n  enabled: false\n  prometheus:\n    enabled: true\n");

        TelemetryRuntime runtime = TelemetryRuntime.start(TelemetrySettings.load(config), root);
        try {
            assertNull(runtime.store());
            assertNull(runtime.metrics());
            assertNull(runtime.metricsAddress());
        } finally {
            runtime.close();
        }
    }

    @Test
    public void enabledRuntimeWritesTraceAndServesPrometheus() throws Exception {
        Path root = folder.newFolder("enabled").toPath();
        int port = freePort();
        Path config = writeConfig("telemetry:\n  enabled: true\n  maxRecentSpans: 5\n  retainDays: 1\n"
                + "  sampleRate: 1.0\n  slowTraceMillis: 0\n"
                + "  prometheus:\n    enabled: true\n    host: 127.0.0.1\n    port: " + port + "\n");

        TelemetryRuntime runtime = TelemetryRuntime.start(TelemetrySettings.load(config), root);
        try {
            assertNotNull(runtime.store());
            assertNotNull(runtime.metrics());
            assertNotNull(runtime.metricsAddress());
            assertTrue(runtime.metricsAddress().startsWith("127.0.0.1:" + port + "/metrics"));

            try (TelemetrySpan span = runtime.telemetry().startSpan("user.turn", TelemetrySpanKindEnum.TURN)) {
                span.attribute("k", "v");
            }
            assertEquals(1, runtime.store().size());

            assertEquals("ok\n", get("http://127.0.0.1:" + port + "/healthz"));
            String metrics = get("http://127.0.0.1:" + port + "/metrics");
            assertTrue(metrics.contains("clara_turn_duration"));
        } finally {
            runtime.close();
        }
    }

    @Test
    public void occupiedPortDegradesWithoutBlockingStartup() throws Exception {
        Path root = folder.newFolder("occupied").toPath();
        try (ServerSocket socket = new ServerSocket(0)) {
            int port = socket.getLocalPort();
            Path config = writeConfig("telemetry:\n  enabled: true\n  prometheus:\n"
                    + "    enabled: true\n    host: 127.0.0.1\n    port: " + port + "\n");
            TelemetryRuntime runtime = TelemetryRuntime.start(TelemetrySettings.load(config), root);
            try {
                assertNotNull(runtime.store());
                assertNull("an occupied port must only disable the metrics endpoint",
                        runtime.metricsAddress());
            } finally {
                runtime.close();
            }
        }
    }

    @Test
    public void httpServerValidatesSettingsAndAnswersBothRoutes() throws Exception {
        assertNull(TelemetryHttpServer.start(null, new TelemetryMetrics()));
        assertNull(TelemetryHttpServer.start(TelemetryPrometheusSettings.defaults(), new TelemetryMetrics()));
        assertNull("no metrics collector means no endpoint",
                TelemetryHttpServer.start(prometheus(freePort()), null));

        int port = freePort();
        TelemetryMetrics metrics = new TelemetryMetrics();
        TelemetryHttpServer server = TelemetryHttpServer.start(prometheus(port), metrics);
        assertNotNull(server);
        try {
            assertEquals("127.0.0.1:" + port + "/metrics", server.address());
            assertEquals("ok\n", get("http://127.0.0.1:" + port + "/healthz"));
            assertNotNull(get("http://127.0.0.1:" + port + "/metrics"));
        } finally {
            server.close();
        }
    }

    private Path writeConfig(String telemetryYaml) throws Exception {
        Path config = folder.newFile("clara-" + System.nanoTime() + ".yml").toPath();
        Files.write(config, telemetryYaml.getBytes(StandardCharsets.UTF_8));
        return config;
    }

    private static TelemetryPrometheusSettings prometheus(int port) throws Exception {
        Path node = Files.createTempFile("clara-prometheus", ".yml");
        try {
            Files.write(node, ("prometheus:\n  enabled: true\n  host: 127.0.0.1\n  port: " + port + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            com.fasterxml.jackson.databind.JsonNode tree = new com.fasterxml.jackson.databind.ObjectMapper(
                    new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(node.toFile());
            return TelemetryPrometheusSettings.load(tree);
        } finally {
            Files.deleteIfExists(node);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String get(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
