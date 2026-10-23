package com.clara.telemetry;

import com.clara.core.telemetry.ITelemetrySink;
import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.core.telemetry.TelemetryStatusEnum;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 采样器测试：成功链路可按比例丢弃，错误 / 取消 / 慢链路必须保留。
 */
public class SamplingTelemetrySinkTest {

    @Test
    public void keepsErrorsWhenSampleRateIsZero() {
        RecordingSink sink = new RecordingSink();
        SamplingTelemetrySink sampler = new SamplingTelemetrySink(0.0d, 1000L,
                Collections.singletonList(sink));

        sampler.onSpan(event("t1", "s1", null, TelemetryStatusEnum.OK, 10L));
        sampler.onSpan(event("t2", "s3", "s2", TelemetryStatusEnum.ERROR, 10L));
        sampler.onSpan(event("t2", "s2", null, TelemetryStatusEnum.OK, 20L));
        sampler.close();

        assertEquals(2, sink.events.size());
        assertEquals("s3", sink.events.get(0).getSpanId());
        assertEquals("s2", sink.events.get(1).getSpanId());
    }

    @Test
    public void keepsSlowTraceWhenSampleRateIsZero() {
        RecordingSink sink = new RecordingSink();
        SamplingTelemetrySink sampler = new SamplingTelemetrySink(0.0d, 100L,
                Collections.singletonList(sink));

        sampler.onSpan(event("slow", "s1", null, TelemetryStatusEnum.OK, 150L));

        assertEquals(1, sink.events.size());
    }

    @Test
    public void passesEverythingAtFullSampleRate() {
        RecordingSink sink = new RecordingSink();
        SamplingTelemetrySink sampler = new SamplingTelemetrySink(1.0d, 1000L,
                Collections.singletonList(sink));

        sampler.onSpan(event("t1", "s1", null, TelemetryStatusEnum.OK, 10L));
        sampler.onSpan(event("t1", "s2", "s1", TelemetryStatusEnum.OK, 10L));

        assertEquals(2, sink.events.size());
    }

    private static TelemetryEvent event(String traceId, String spanId, String parent,
            TelemetryStatusEnum status, long duration) {
        return TelemetryEvent.builder()
                .traceId(traceId)
                .spanId(spanId)
                .parentSpanId(parent)
                .name("model.call")
                .kind(TelemetrySpanKindEnum.MODEL)
                .startEpochMillis(1000L)
                .durationMillis(duration)
                .status(status)
                .build();
    }

    private static final class RecordingSink implements ITelemetrySink {

        private final List<TelemetryEvent> events = new ArrayList<>();

        @Override
        public void onSpan(TelemetryEvent event) {
            events.add(event);
        }
    }
}