package com.clara.transport.http;

import com.clara.exception.HttpTransportException;
import com.clara.core.transport.IExecutionGuard;
import com.clara.core.transport.sse.ISseEventListener;
import com.clara.core.transport.sse.SseEvent;
import com.clara.core.transport.sse.SseParserConfig;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Behavior tests for the shared SSE parser.
 */
public class DefaultSseEventParserTest {

    @Test
    public void singleDataEventDispatchedOnBlankLine() {
        List<SseEvent> events = parse("data: hello\n\n", config(false));

        assertEquals(1, events.size());
        assertEquals("hello", events.get(0).getData());
        assertEquals("message", events.get(0).getEvent());
        assertNull(events.get(0).getId());
        assertNull(events.get(0).getRetryMillis());
    }

    @Test
    public void multipleDataLinesJoinedWithNewline() {
        List<SseEvent> events = parse("data: line1\ndata: line2\n\n", config(false));

        assertEquals(1, events.size());
        assertEquals("line1\nline2", events.get(0).getData());
    }

    @Test
    public void idAndRetryCaptured() {
        List<SseEvent> events = parse("id: 42\nretry: 1500\ndata: x\n\n", config(false));

        assertEquals(1, events.size());
        assertEquals("42", events.get(0).getId());
        assertEquals(Long.valueOf(1500L), events.get(0).getRetryMillis());
        assertEquals("x", events.get(0).getData());
    }

    @Test
    public void commentLinesIgnored() {
        List<SseEvent> events = parse(": keep-alive\ndata: x\n\n", config(false));

        assertEquals(1, events.size());
        assertEquals("x", events.get(0).getData());
    }

    @Test
    public void bomAndCrLfHandled() {
        List<SseEvent> events = parse("\ufeffdata: hi\r\n\r\n", config(false));

        assertEquals(1, events.size());
        assertEquals("hi", events.get(0).getData());
    }

    @Test
    public void customEventNamePreserved() {
        List<SseEvent> events = parse("event: delta\ndata: x\n\n", config(false));

        assertEquals(1, events.size());
        assertEquals("delta", events.get(0).getEvent());
    }

    @Test
    public void idContainingNullCharacterIgnored() {
        List<SseEvent> events = parse("id: a\u0000b\ndata: x\n\n", config(false));

        assertEquals(1, events.size());
        assertNull(events.get(0).getId());
    }

    @Test
    public void trailingEventDroppedWhenConfiguredForModelSemantics() {
        List<SseEvent> events = parse("data: hello\n", config(false));

        assertTrue(events.isEmpty());
    }

    @Test
    public void trailingEventDispatchedWhenConfiguredForMcpSemantics() {
        List<SseEvent> events = parse("data: hello", config(true));

        assertEquals(1, events.size());
        assertEquals("hello", events.get(0).getData());
    }

    @Test
    public void oversizedEventRejected() {
        SseParserConfig config = new SseParserConfig(64, 4, 10, 1024, false);
        try {
            parse("data: 12345\n\n", config);
            fail("expected response too large");
        } catch (HttpTransportException expected) {
            assertEquals(HttpTransportException.Kind.RESPONSE_TOO_LARGE, expected.getKind());
        }
    }

    @Test
    public void oversizedLineRejected() {
        SseParserConfig config = new SseParserConfig(4, 64, 10, 1024, false);
        try {
            parse("data: 12345\n\n", config);
            fail("expected response too large");
        } catch (HttpTransportException expected) {
            assertEquals(HttpTransportException.Kind.RESPONSE_TOO_LARGE, expected.getKind());
        }
    }

    @Test
    public void tooManyEventsRejected() {
        SseParserConfig config = new SseParserConfig(64, 64, 1, 1024, false);
        try {
            parse("data: a\n\ndata: b\n\n", config);
            fail("expected response too large");
        } catch (HttpTransportException expected) {
            assertEquals(HttpTransportException.Kind.RESPONSE_TOO_LARGE, expected.getKind());
        }
    }

    @Test
    public void invalidUtf8Rejected() {
        byte[] bytes = new byte[] {'d', 'a', 't', 'a', ':', ' ', (byte) 0xC3, 0x28, '\n', '\n'};
        try {
            parse(bytes, config(false));
            fail("expected protocol error");
        } catch (HttpTransportException expected) {
            assertEquals(HttpTransportException.Kind.PROTOCOL_ERROR, expected.getKind());
        }
    }

    @Test
    public void cancelledGuardStopsParsing() {
        IExecutionGuard cancelled = new IExecutionGuard() {
            public boolean isCancelled() {
                return true;
            }

            public long remainingMillis() {
                return 1000L;
            }
        };
        try {
            new DefaultSseEventParser(config(false)).parse(
                    input("data: x\n\n"), cancelled, collectingListener(new ArrayList<>()));
            fail("expected cancellation");
        } catch (HttpTransportException expected) {
            assertEquals(HttpTransportException.Kind.CANCELLED, expected.getKind());
        }
    }

    @Test
    public void listenerCanStopStream() {
        final List<SseEvent> events = new ArrayList<>();
        int dispatched = new DefaultSseEventParser(config(false)).parse(
                input("data: one\n\ndata: two\n\n"), IExecutionGuard.NONE,
                new ISseEventListener() {
                    public void onStart(int status, Map<String, List<String>> headers) {
                    }

                    public boolean onEvent(SseEvent event) {
                        events.add(event);
                        return false;
                    }
                });

        assertEquals(1, dispatched);
        assertEquals(1, events.size());
        assertEquals("one", events.get(0).getData());
    }

    private static SseParserConfig config(boolean dispatchFinalEventOnEof) {
        return new SseParserConfig(64, 64, 100, 1024, dispatchFinalEventOnEof);
    }

    private static List<SseEvent> parse(String text, SseParserConfig config) {
        return parse(text.getBytes(StandardCharsets.UTF_8), config);
    }

    private static List<SseEvent> parse(byte[] bytes, SseParserConfig config) {
        final List<SseEvent> events = new ArrayList<>();
        int dispatched = new DefaultSseEventParser(config).parse(
                new ByteArrayInputStream(bytes), IExecutionGuard.NONE,
                collectingListener(events));
        assertEquals(events.size(), dispatched);
        return events;
    }

    private static ByteArrayInputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ISseEventListener collectingListener(final List<SseEvent> events) {
        return new ISseEventListener() {
            public void onStart(int status, Map<String, List<String>> headers) {
            }

            public boolean onEvent(SseEvent event) {
                events.add(event);
                return true;
            }
        };
    }
}
