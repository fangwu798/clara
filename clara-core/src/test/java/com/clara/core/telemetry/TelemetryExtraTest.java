package com.clara.core.telemetry;

import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 遥测旁路的补充契约：组合接收端的隔离性、显式链路上下文与 Span 的写入边界。
 *
 * <p>观测是旁路：任何接收端或属性写入都不能影响业务线程，也不能把凭据写进链路；
 * 这些约束靠单测固定，避免后续重构破坏。
 */
public class TelemetryExtraTest {

    @Test
    public void compositeSinkDispatchesToAllAndIsolatesFailures() {
        RecordingSink first = new RecordingSink();
        RecordingSink second = new RecordingSink();
        CompositeTelemetrySink sink = new CompositeTelemetrySink(Arrays.asList(
                first, null, event -> {
                    throw new IllegalStateException("boom");
                }, second));

        Telemetry telemetry = new Telemetry(sink);
        try (TelemetrySpan span = telemetry.startSpan("model.call", TelemetrySpanKindEnum.MODEL)) {
            span.attribute("model", "m");
        }

        assertEquals(1, first.events.size());
        assertEquals(1, second.events.size());
        assertEquals(first.events.get(0).getSpanId(), second.events.get(0).getSpanId());
    }

    @Test
    public void compositeSinkToleratesEmptyAndNullDelegateLists() {
        CompositeTelemetrySink empty = new CompositeTelemetrySink(Collections.<ITelemetrySink>emptyList());
        CompositeTelemetrySink none = new CompositeTelemetrySink(null);

        empty.onSpan(event());
        none.onSpan(event());

        CompositeTelemetrySink nullOnly = new CompositeTelemetrySink(
                Arrays.<ITelemetrySink>asList(null, null));
        nullOnly.onSpan(event());
    }

    @Test
    public void compositeSinkKeepsDelegateSnapshot() {
        RecordingSink first = new RecordingSink();
        List<ITelemetrySink> delegates = new ArrayList<>();
        delegates.add(first);
        CompositeTelemetrySink sink = new CompositeTelemetrySink(delegates);

        delegates.clear();
        sink.onSpan(event());

        assertEquals(1, first.events.size());
    }

    @Test
    public void telemetryContextValidatesAndTrimsValues() {
        TelemetryContext context = new TelemetryContext(" trace ", " span ");

        assertEquals("trace", context.getTraceId());
        assertEquals("span", context.getSpanId());
        assertEquals("trace/span", context.toString());
        assertThrows(IllegalArgumentException.class, () -> new TelemetryContext(null, "span"));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryContext("  ", "span"));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryContext("trace", null));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryContext("trace", " "));
    }

    @Test
    public void explicitContextCreatesChildAndRestoresParent() {
        RecordingSink sink = new RecordingSink();
        Telemetry telemetry = new Telemetry(sink);
        assertNull(telemetry.currentSpan());
        assertNull(telemetry.currentTraceId());
        assertNull(telemetry.currentContext());

        TelemetrySpan root = telemetry.startSpan("root", TelemetrySpanKindEnum.CONVERSATION);
        TelemetryContext context = telemetry.currentContext();
        assertNotNull(context);
        assertEquals(telemetry.currentTraceId(), context.getTraceId());

        try (TelemetrySpan child = telemetry.startSpan("child", TelemetrySpanKindEnum.MODEL, context)) {
            assertSame(child, telemetry.currentSpan());
            assertEquals(context.getTraceId(), telemetry.currentTraceId());
        }

        assertSame(root, telemetry.currentSpan());
        root.close();
        assertNull(telemetry.currentSpan());
    }

    @Test
    public void explicitNullContextStartsNewRootTrace() {
        RecordingSink sink = new RecordingSink();
        Telemetry telemetry = new Telemetry(sink);

        try (TelemetrySpan span = telemetry.startSpan("isolated", TelemetrySpanKindEnum.OTHER, null)) {
            assertSame(span, telemetry.currentSpan());
        }

        TelemetryEvent event = sink.events.get(0);
        assertNull(event.getParentSpanId());
        assertEquals(32, event.getTraceId().length());
        assertEquals(16, event.getSpanId().length());
    }

    @Test
    public void unknownKindFallsBackToOther() {
        RecordingSink sink = new RecordingSink();
        Telemetry telemetry = new Telemetry(sink);

        try (TelemetrySpan span = telemetry.startSpan(" stage ", null)) {
            assertSame(TelemetrySpanKindEnum.OTHER, span.kind());
        }

        assertEquals(TelemetrySpanKindEnum.OTHER, sink.events.get(0).getKind());
        assertEquals("stage", sink.events.get(0).getName());
    }

    @Test
    public void blankSpanNameIsRejectedOnlyWhenSinkExists() {
        Telemetry telemetry = new Telemetry(new RecordingSink());
        assertThrows(IllegalArgumentException.class,
                () -> telemetry.startSpan(" ", TelemetrySpanKindEnum.MODEL));
        assertThrows(IllegalArgumentException.class,
                () -> telemetry.startSpan(null, TelemetrySpanKindEnum.MODEL, new TelemetryContext("t", "s")));

        try (TelemetrySpan ignored = Telemetry.noop().startSpan("  ", TelemetrySpanKindEnum.MODEL)) {
            assertNotNull(ignored);
        }
    }

    @Test
    public void noopSpanWritesAreIgnored() {
        TelemetrySpan span = Telemetry.noop().startSpan("ignored", TelemetrySpanKindEnum.MODEL);

        assertSame(span, span.attribute("k", "v"));
        assertSame(span, span.attribute(null, "v"));
        assertSame(span, span.status(TelemetryStatusEnum.ERROR));
        assertSame(span, span.error(new IllegalStateException("ignored")));
        assertSame(span, span.cancelled());
        span.close();
        span.close();

        assertEquals(TelemetryStatusEnum.OK, span.status());
        assertTrue(span.attributes().isEmpty());
    }

    @Test
    public void attributesIgnoreBlankKeysAndNullValuesAndTruncateLongText() {
        RecordingSink sink = new RecordingSink();
        Telemetry telemetry = new Telemetry(sink);

        StringBuilder longText = new StringBuilder();
        for (int index = 0; index < 600; index++) {
            longText.append('x');
        }

        try (TelemetrySpan span = telemetry.startSpan("model.call", TelemetrySpanKindEnum.MODEL)) {
            span.attribute(null, "v");
            span.attribute("  ", "v");
            span.attribute("empty", null);
            span.attribute(" padded ", longText.toString());
            span.status(null);
        }

        TelemetryEvent event = sink.events.get(0);
        assertFalse(event.getAttributes().containsKey("empty"));
        assertFalse(event.getAttributes().containsKey(""));
        String value = event.getAttributes().get("padded");
        assertEquals(515, value.length());
        assertTrue(value.endsWith("..."));
        assertEquals(TelemetryStatusEnum.OK, event.getStatus());
    }

    @Test
    public void cancelledStatusAndNullErrorsAreHandled() {
        RecordingSink sink = new RecordingSink();
        Telemetry telemetry = new Telemetry(sink);

        try (TelemetrySpan first = telemetry.startSpan("cancelled", TelemetrySpanKindEnum.TOOL)) {
            first.cancelled();
            first.error((ClaraError) null);
        }
        try (TelemetrySpan second = telemetry.startSpan("null-error", TelemetrySpanKindEnum.TOOL)) {
            second.error((Throwable) null);
        }

        assertEquals(TelemetryStatusEnum.CANCELLED, sink.events.get(0).getStatus());
        assertNull(sink.events.get(0).getErrorCode());
        assertEquals(TelemetryStatusEnum.OK, sink.events.get(1).getStatus());
    }

    @Test
    public void claraExceptionReusesStructuredError() {
        RecordingSink sink = new RecordingSink();
        Telemetry telemetry = new Telemetry(sink);
        ClaraError error = ClaraError.builder(ErrorCodes.MODEL_PROVIDER_FAILED, ErrorCategoryEnum.EXTERNAL_SERVICE)
                .safeMessage("平台 503")
                .build();

        try (TelemetrySpan span = telemetry.startSpan("model.call", TelemetrySpanKindEnum.MODEL)) {
            span.error(new ClaraException(error));
        }

        TelemetryEvent event = sink.events.get(0);
        assertEquals(TelemetryStatusEnum.ERROR, event.getStatus());
        assertEquals(ErrorCodes.MODEL_PROVIDER_FAILED, event.getErrorCode());
        assertEquals("EXTERNAL_SERVICE", event.getErrorCategory());
        assertEquals("平台 503", event.getErrorMessage());
    }

    @Test
    public void eventBuilderValidatesRequiredFieldsAndCopiesAttributes() {
        assertThrows(IllegalArgumentException.class, () -> TelemetryEvent.builder()
                .spanId("s").name("n").build());
        assertThrows(IllegalArgumentException.class, () -> TelemetryEvent.builder()
                .traceId("t").name("n").build());
        assertThrows(IllegalArgumentException.class, () -> TelemetryEvent.builder()
                .traceId("t").spanId("s").name(" ").build());

        TelemetryEvent event = TelemetryEvent.builder()
                .traceId("t").spanId("s").name("n").build();
        assertEquals(TelemetrySpanKindEnum.OTHER, event.getKind());
        assertEquals(TelemetryStatusEnum.OK, event.getStatus());
        assertTrue(event.getAttributes().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> event.getAttributes().put("k", "v"));
    }

    private static TelemetryEvent event() {
        return TelemetryEvent.builder().traceId("t").spanId("s").name("n").build();
    }

    private static final class RecordingSink implements ITelemetrySink {

        private final List<TelemetryEvent> events = new ArrayList<>();

        @Override
        public void onSpan(TelemetryEvent event) {
            events.add(event);
        }
    }
}