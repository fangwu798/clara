package com.clara.core.telemetry;

import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ClaraError;
import com.clara.exception.ErrorCodes;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link Telemetry} 的契约测试：父子关系、错误映射、重复关闭与接收端异常。
 */
public class TelemetryTest {

    @Test
    public void buildsParentChildEventsWithSharedTrace() {
        RecordingSink sink = new RecordingSink();
        Telemetry telemetry = new Telemetry(sink);

        try (TelemetrySpan root = telemetry.startSpan("conversation.turn", TelemetrySpanKindEnum.CONVERSATION)) {
            root.attribute("provider", "DEEPSEEK");
            try (TelemetrySpan model = telemetry.startSpan("model.call", TelemetrySpanKindEnum.MODEL)) {
                model.attribute("input_tokens", 12).attribute("output_tokens", 3);
            }
        }

        assertEquals(2, sink.events.size());
        TelemetryEvent root = event(sink.events, "conversation.turn");
        TelemetryEvent model = event(sink.events, "model.call");
        assertEquals("conversation.turn", root.getName());
        assertNull(root.getParentSpanId());
        assertEquals(root.getTraceId(), model.getTraceId());
        assertEquals(root.getSpanId(), model.getParentSpanId());
        assertEquals("DEEPSEEK", root.getAttributes().get("provider"));
        assertEquals("12", model.getAttributes().get("input_tokens"));
        assertTrue(model.getDurationMillis() >= 0L);
    }

    @Test
    public void mapsClaraErrorToCategoryAndCode() {
        RecordingSink sink = new RecordingSink();
        Telemetry telemetry = new Telemetry(sink);
        ClaraError error = ClaraError.builder(ErrorCodes.MODEL_PROVIDER_FAILED,
                        ErrorCategoryEnum.EXTERNAL_SERVICE)
                .safeMessage("平台返回 503")
                .build();

        try (TelemetrySpan span = telemetry.startSpan("model.call", TelemetrySpanKindEnum.MODEL)) {
            span.error(error);
        }

        TelemetryEvent event = sink.events.get(0);
        assertEquals(TelemetryStatusEnum.ERROR, event.getStatus());
        assertEquals("EXTERNAL_SERVICE", event.getErrorCategory());
        assertEquals(ErrorCodes.MODEL_PROVIDER_FAILED, event.getErrorCode());
        assertEquals("平台返回 503", event.getErrorMessage());
    }

    @Test
    public void repeatedCloseEmitsOnlyOnce() {
        RecordingSink sink = new RecordingSink();
        Telemetry telemetry = new Telemetry(sink);
        TelemetrySpan span = telemetry.startSpan("tool.call", TelemetrySpanKindEnum.TOOL);
        span.close();
        span.close();

        assertEquals(1, sink.events.size());
    }

    @Test
    public void sinkFailureDoesNotEscape() {
        Telemetry telemetry = new Telemetry(event -> {
            throw new IllegalStateException("boom");
        });

        try (TelemetrySpan span = telemetry.startSpan("model.call", TelemetrySpanKindEnum.MODEL)) {
            span.attribute("model", "m");
        }
        assertTrue(telemetry.isEnabled());
    }

    @Test
    public void noopTelemetryProducesNothing() {
        RecordingSink sink = new RecordingSink();
        Telemetry telemetry = Telemetry.noop();
        try (TelemetrySpan span = telemetry.startSpan("model.call", TelemetrySpanKindEnum.MODEL)) {
            span.error(new IllegalStateException("ignored"));
        }

        assertFalse(telemetry.isEnabled());
        assertTrue(sink.events.isEmpty());
        assertNull(telemetry.currentSpan());
    }

    @Test
    public void sanitizesCredentialsAndNewlines() {
        RecordingSink sink = new RecordingSink();
        Telemetry telemetry = new Telemetry(sink);
        try (TelemetrySpan span = telemetry.startSpan("model.call", TelemetrySpanKindEnum.MODEL)) {
            span.error(new IllegalStateException("Authorization: Bearer abc123\nsk-secret"));
        }

        TelemetryEvent event = sink.events.get(0);
        assertNotNull(event.getErrorMessage());
        assertFalse(event.getErrorMessage().contains("abc123"));
        assertFalse(event.getErrorMessage().contains("sk-secret"));
    }

    private static TelemetryEvent event(List<TelemetryEvent> events, String name) {
        for (TelemetryEvent event : events) {
            if (name.equals(event.getName())) {
                return event;
            }
        }
        throw new AssertionError("missing event: " + name);
    }

    private static final class RecordingSink implements ITelemetrySink {

        private final List<TelemetryEvent> events = new ArrayList<>();

        @Override
        public void onSpan(TelemetryEvent event) {
            events.add(event);
        }
    }
}
