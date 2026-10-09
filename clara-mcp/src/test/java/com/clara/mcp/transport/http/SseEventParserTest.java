package com.clara.mcp.transport.http;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link SseEventParser} 的 SSE 事件解析测试：data / id / retry 字段与事件分帧。
 */
public class SseEventParserTest {

    @Test
    public void singleDataEventDispatchedOnBlankLine() {
        SseEventParser parser = new SseEventParser(1024);

        assertNull(parser.accept("data: hello"));
        SseEvent event = parser.accept("");

        assertEquals("hello", event.getData());
        assertNull(event.getId());
        assertNull(event.getRetryMillis());
    }

    @Test
    public void dataEventDispatchedOnNullLine() {
        SseEventParser parser = new SseEventParser(1024);
        parser.accept("data: hello");

        SseEvent event = parser.accept(null);

        assertEquals("hello", event.getData());
    }

    @Test
    public void multipleDataLinesJoinedWithNewline() {
        SseEventParser parser = new SseEventParser(1024);
        parser.accept("data: line1");
        parser.accept("data: line2");

        SseEvent event = parser.accept("");

        assertEquals("line1\nline2", event.getData());
    }

    @Test
    public void commentLinesIgnored() {
        SseEventParser parser = new SseEventParser(1024);
        parser.accept(": this is a comment");

        assertNull(parser.accept(""));
    }

    @Test
    public void idAndRetryCaptured() {
        SseEventParser parser = new SseEventParser(1024);
        parser.accept("id: 42");
        parser.accept("retry: 1500");
        parser.accept("data: x");

        SseEvent event = parser.accept("");

        assertEquals("42", event.getId());
        assertEquals(Long.valueOf(1500L), event.getRetryMillis());
        assertEquals("x", event.getData());
    }

    @Test
    public void valueWithoutLeadingSpaceAccepted() {
        SseEventParser parser = new SseEventParser(1024);
        parser.accept("data:no-space");

        assertEquals("no-space", parser.accept("").getData());
    }

    @Test
    public void parserResetsStateBetweenEvents() {
        SseEventParser parser = new SseEventParser(1024);
        parser.accept("id: 1");
        parser.accept("data: first");
        assertEquals("first", parser.accept("").getData());
        parser.accept("data: second");

        SseEvent second = parser.accept("");

        assertEquals("second", second.getData());
        assertNull(second.getId());
    }

    @Test
    public void idContainingNullCharacterIgnored() {
        SseEventParser parser = new SseEventParser(1024);
        parser.accept("id: a\u0000b");
        parser.accept("data: x");

        assertNull(parser.accept("").getId());
    }

    @Test
    public void oversizedDataRejected() {
        SseEventParser parser = new SseEventParser(4);

        try {
            parser.accept("data: 12345");
            fail("expected SseEventTooLargeException");
        } catch (SseEventTooLargeException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void nonPositiveLimitRejected() {
        try {
            new SseEventParser(0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }
}
