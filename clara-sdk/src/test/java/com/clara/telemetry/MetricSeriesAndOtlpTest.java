package com.clara.telemetry;

import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.core.telemetry.TelemetryStatusEnum;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Pure logic tests for metric series, histograms, and OTLP encoding (no network). */
public class MetricSeriesAndOtlpTest {

    @Test
    public void seriesNormalizesLabelsAndComparesByContent() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(" provider ", "DEEPSEEK");
        labels.put(" ", "ignored");
        labels.put("model", "  ");
        labels.put(null, "ignored");

        MetricSeries series = MetricSeries.of(" clara.model.calls ", labels);

        assertEquals("clara.model.calls", series.getName());
        assertEquals("clara_model_calls", series.prometheusName());
        assertEquals("DEEPSEEK", series.label("provider"));
        assertEquals("unknown", series.label("model"));
        assertFalse(series.getLabels().containsKey(""));

        MetricSeries same = MetricSeries.of("clara.model.calls",
                map("model", "unknown", "provider", "DEEPSEEK"));
        assertEquals(series, series);
        assertFalse(series.equals(null));
        assertFalse(series.equals("other"));
        assertEquals(series.hashCode(), same.hashCode());
        assertEquals("natural order must agree with equals", 0, series.compareTo(same));
        assertTrue(series.compareTo(MetricSeries.of("clara.turn", null)) < 0);
        assertThrows(IllegalArgumentException.class, () -> MetricSeries.of(" ", null));
        assertThrows(IllegalArgumentException.class, () -> MetricSeries.of(null, null));
    }

    @Test
    public void histogramBucketsAndMaximum() {
        MetricHistogram histogram = new MetricHistogram();
        histogram.record(-5L);
        histogram.record(10L);
        histogram.record(5000L);
        histogram.record(30_001L);

        HistogramSnapshot snapshot = histogram.snapshot();
        assertEquals(4L, snapshot.getCount());
        assertEquals(35_011L, snapshot.getSum());
        assertEquals(30_001L, histogram.maximum());
        assertEquals(12, snapshot.getBuckets().length);
        // -5 is normalized to 0 and 10 falls into the first bucket as well.
        assertEquals(2L, snapshot.getBuckets()[0]);
        assertEquals(1L, snapshot.getBuckets()[snapshot.getBuckets().length - 1]);
    }

    @Test
    public void otlpBuilderNormalizesIdsAndTypedAttributes() throws Exception {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("bool", "TRUE");
        attributes.put("number", " 42 ");
        attributes.put("text", "hello");
        attributes.put("empty", null);
        TelemetryEvent event = TelemetryEvent.builder()
                .traceId("abc")
                .spanId("def")
                .parentSpanId("  ")
                .name("model.call")
                .kind(TelemetrySpanKindEnum.MODEL)
                .startEpochMillis(1L)
                .durationMillis(2L)
                .status(TelemetryStatusEnum.ERROR)
                .errorMessage("boom")
                .attributes(attributes)
                .build();

        String json = OtlpJsonBuilder.build(Arrays.asList(event), "clara-test");

        assertTrue(json.contains("\"traceId\":\"00000000000000000000000000000abc\""));
        assertTrue(json.contains("\"spanId\":\"0000000000000def\""));
        assertTrue(json.contains("\"boolValue\":true"));
        assertTrue(json.contains("\"intValue\":\"42\""));
        assertTrue(json.contains("\"code\":2"));
        assertTrue(json.contains("\"message\":\"boom\""));
        assertFalse(json.contains("parentSpanId"));
    }

    @Test
    public void otlpBuilderHandlesCancelledStatusAndLongIds() throws Exception {
        TelemetryEvent event = TelemetryEvent.builder()
                .traceId("0123456789abcdef0123456789abcdefEXTRA")
                .spanId("0123456789abcdef0123")
                .name("tool.call")
                .kind(TelemetrySpanKindEnum.TOOL)
                .startEpochMillis(0L)
                .durationMillis(0L)
                .status(TelemetryStatusEnum.CANCELLED)
                .attributes(Collections.<String, String>emptyMap())
                .build();

        String json = OtlpJsonBuilder.build(Collections.singletonList(event), "s");
        assertTrue(json.contains("\"kind\":3"));
        assertTrue(json.contains("\"code\":0"));
        assertTrue(json.contains("\"traceId\":\"0123456789abcdef0123456789abcdef\""));
        assertTrue(json.contains("\"spanId\":\"0123456789abcdef\""));
    }

    private static Map<String, String> map(String... keys) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keys.length; index += 2) {
            values.put(keys[index], keys[index + 1]);
        }
        return values;
    }
}
