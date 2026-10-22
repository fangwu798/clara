package com.clara.telemetry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link TelemetrySettings} 的配置解析测试：缺省、合法值与非法值都不应阻断启动。
 */
public class TelemetrySettingsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void missingFileFallsBackToDefaults() {
        TelemetrySettings settings = TelemetrySettings.load(folder.getRoot().toPath().resolve("absent.yml"));

        assertTrue(settings.isEnabled());
        assertEquals(500, settings.getMaxRecentSpans());
        assertEquals(7, settings.getRetainDays());
    }

    @Test
    public void readsConfiguredValues() throws Exception {
        Path config = write("telemetry:\n  enabled: false\n  maxRecentSpans: 42\n  retainDays: 0\n");

        TelemetrySettings settings = TelemetrySettings.load(config);

        assertFalse(settings.isEnabled());
        assertEquals(42, settings.getMaxRecentSpans());
        assertEquals(0, settings.getRetainDays());
    }

    @Test
    public void invalidValuesFallBackPerField() throws Exception {
        Path config = write("telemetry:\n  enabled: maybe\n  maxRecentSpans: -3\n  retainDays: bad\n");

        TelemetrySettings settings = TelemetrySettings.load(config);

        assertTrue(settings.isEnabled());
        assertEquals(500, settings.getMaxRecentSpans());
        assertEquals(7, settings.getRetainDays());
    }

    @Test
    public void missingNodeFallsBackToDefaults() throws Exception {
        TelemetrySettings settings = TelemetrySettings.load(write("model:\n  alias: deepseek\n"));

        assertTrue(settings.isEnabled());
        assertEquals(500, settings.getMaxRecentSpans());
    }

    @Test
    public void readsSamplingAndExportSettings() throws Exception {
        Path config = write("telemetry:\n"
                + "  sampleRate: 0.25\n"
                + "  slowTraceMillis: 3000\n"
                + "  otlp:\n"
                + "    enabled: true\n"
                + "    endpoint: http://collector:4318\n"
                + "    headers:\n"
                + "      x-api-key: secret\n"
                + "    timeoutMillis: 1200\n"
                + "    serviceName: clara-test\n"
                + "    flushIntervalMillis: 250\n"
                + "  prometheus:\n"
                + "    enabled: true\n"
                + "    host: 0.0.0.0\n"
                + "    port: 9500\n");

        TelemetrySettings settings = TelemetrySettings.load(config);

        assertEquals(0.25d, settings.getSampleRate(), 0.0001d);
        assertEquals(3000, settings.getSlowTraceMillis());
        assertTrue(settings.getOtlp().isEnabled());
        assertEquals("http://collector:4318", settings.getOtlp().getEndpoint());
        assertEquals("secret", settings.getOtlp().getHeaders().get("x-api-key"));
        assertEquals(1200, settings.getOtlp().getTimeoutMillis());
        assertEquals("clara-test", settings.getOtlp().getServiceName());
        assertEquals(250, settings.getOtlp().getFlushIntervalMillis());
        assertTrue(settings.getPrometheus().isEnabled());
        assertEquals("0.0.0.0", settings.getPrometheus().getHost());
        assertEquals(9500, settings.getPrometheus().getPort());
    }

    @Test
    public void invalidSamplingAndExportValuesFallBack() throws Exception {
        Path config = write("telemetry:\n"
                + "  sampleRate: 1.5\n"
                + "  slowTraceMillis: -1\n"
                + "  otlp:\n"
                + "    enabled: maybe\n"
                + "    endpoint: '  '\n"
                + "    timeoutMillis: 0\n"
                + "  prometheus:\n"
                + "    enabled: true\n"
                + "    host: ''\n"
                + "    port: 70000\n");

        TelemetrySettings settings = TelemetrySettings.load(config);

        assertEquals(1.0d, settings.getSampleRate(), 0.0001d);
        assertEquals(10_000, settings.getSlowTraceMillis());
        assertFalse(settings.getOtlp().isEnabled());
        assertEquals("http://localhost:4318", settings.getOtlp().getEndpoint());
        assertEquals(5000, settings.getOtlp().getTimeoutMillis());
        assertEquals("127.0.0.1", settings.getPrometheus().getHost());
        assertEquals(9464, settings.getPrometheus().getPort());
    }
    private Path write(String yaml) throws Exception {
        Path file = folder.newFile("clara.yml").toPath();
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}