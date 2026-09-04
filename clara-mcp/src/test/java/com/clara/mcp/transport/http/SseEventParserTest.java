package com.clara.mcp.transport.http;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class SseEventParserTest {
    @Test
    public void parsesMultilineDataAndMetadata() {
        SseEventParser parser = new SseEventParser(32);
        assertNull(parser.accept(": heartbeat"));
        assertNull(parser.accept("id: event-1"));
        assertNull(parser.accept("retry: 25"));
        assertNull(parser.accept("data: first"));
        assertNull(parser.accept("data:second"));

        SseEvent event = parser.accept("");
        assertEquals("first\nsecond", event.getData());
        assertEquals("event-1", event.getId());
        assertEquals(Long.valueOf(25L), event.getRetryMillis());
    }

    @Test
    public void dispatchesFinalEventAtEndOfStream() {
        SseEventParser parser = new SseEventParser(32);
        parser.accept("data: final");
        assertEquals("final", parser.accept(null).getData());
        assertNull(parser.accept(null));
    }

    @Test
    public void ignoresInvalidRetryAndRejectsOversizedEvent() {
        SseEventParser parser = new SseEventParser(4);
        assertNull(parser.accept("retry: later"));
        try {
            parser.accept("data: 12345");
            fail("expected size rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("SSE event is too large", expected.getMessage());
        }
    }
}
