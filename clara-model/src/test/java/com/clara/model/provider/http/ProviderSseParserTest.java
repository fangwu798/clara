package com.clara.model.provider.http;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.clara.model.provider.http.event.ProviderSseEvent;
import com.clara.model.provider.http.event.ProviderSseParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProviderSseParserTest {
    @Test public void parsesUtf8FieldsCommentsAndMixedLineEndings() {
        String input = "\ufeff: comment\r\nevent: token\rdata: \u4f60\ndata:\u597d\r\n"
                + "id: event-1\nretry: 250\n\n";
        final List<ProviderSseEvent> events = new ArrayList<ProviderSseEvent>();
        int count = parser().parse(bytes(input), context(), new ProviderSseListener() {
            public boolean onEvent(ProviderSseEvent event) {
                events.add(event);
                return true;
            }
        });

        assertEquals(1, count);
        assertEquals(1, events.size());
        assertEquals("token", events.get(0).getEvent());
        assertEquals("\u4f60\n\u597d", events.get(0).getData());
        assertEquals("event-1", events.get(0).getId());
        assertEquals(Long.valueOf(250L), events.get(0).getRetryMillis());
    }

    @Test public void discardsUnterminatedTailAtEof() {
        final List<String> data = new ArrayList<String>();
        int count = parser().parse(bytes("data: first\n\ndata: second"), context(),
                new ProviderSseListener() {
                    public boolean onEvent(ProviderSseEvent event) {
                        data.add(event.getData());
                        return true;
                    }
                });

        assertEquals(1, count);
        assertEquals(1, data.size());
        assertEquals("first", data.get(0));
    }

    @Test public void allowsListenerToStop() {
        final List<String> data = new ArrayList<String>();
        int count = parser().parse(bytes("data: first\n\ndata: second\n\n"), context(),
                new ProviderSseListener() {
                    public boolean onEvent(ProviderSseEvent event) {
                        data.add(event.getData());
                        return false;
                    }
                });

        assertEquals(1, count);
        assertEquals(1, data.size());
        assertEquals("first", data.get(0));
    }

    @Test public void handlesEmptyDataAndIgnoresInvalidMetadata() {
        final List<ProviderSseEvent> events = new ArrayList<ProviderSseEvent>();
        String input = "id: bad\u0000id\nretry: -1\ndata:\n\n";
        parser().parse(bytes(input), context(), new ProviderSseListener() {
            public boolean onEvent(ProviderSseEvent event) {
                events.add(event);
                return true;
            }
        });

        assertEquals(1, events.size());
        assertEquals("message", events.get(0).getEvent());
        assertEquals("", events.get(0).getData());
        assertNull(events.get(0).getId());
        assertNull(events.get(0).getRetryMillis());
    }

    @Test public void enforcesLineEventCountAndTotalLimits() {
        assertCode(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, new Runnable() { public void run() {
            new ProviderSseParser(8, 8, 2, 100).parse(bytes("data: abc\n\n"), context(), sink());
        }});
        assertCode(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, new Runnable() { public void run() {
            new ProviderSseParser(10, 20, 2, 100).parse(
                    bytes("data:12345\ndata:56789\n\n"), context(), sink());
        }});
        assertCode(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, new Runnable() { public void run() {
            new ProviderSseParser(16, 16, 1, 100).parse(
                    bytes("data:a\n\ndata:b\n\n"), context(), sink());
        }});
        assertCode(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, new Runnable() { public void run() {
            new ProviderSseParser(4, 8, 2, 8).parse(bytes(":\n:\n:\n:\n:"), context(), sink());
        }});
    }

    @Test public void rejectsMalformedUtf8AndMapsIoFailure() {
        assertCode(ErrorCodes.MODEL_PROTOCOL_ERROR, new Runnable() { public void run() {
            byte[] invalid = new byte[] {'d', 'a', 't', 'a', ':', (byte) 0xc3, 0x28, '\n', '\n'};
            parser().parse(new ByteArrayInputStream(invalid), context(), sink());
        }});
        assertCode(ErrorCodes.MODEL_TRANSPORT_IO_ERROR, new Runnable() { public void run() {
            parser().parse(new InputStream() {
                public int read() throws IOException { throw new IOException("secret diagnostic"); }
            }, context(), sink());
        }});
    }

    @Test public void enforcesCancellationAndDeadlineDuringReads() {
        final CancellationToken token = new CancellationToken();
        assertCode(ErrorCodes.INVOCATION_CANCELLED, new Runnable() { public void run() {
            parser().parse(new InputStream() {
                public int read() {
                    token.cancel();
                    return 'd';
                }
            }, new InvocationContext("tenant", null, null, "trace",
                    System.currentTimeMillis() + 5000, token), sink());
        }});
        assertCode(ErrorCodes.MODEL_REQUEST_TIMEOUT, new Runnable() { public void run() {
            parser().parse(bytes("data:x\n\n"), new InvocationContext("tenant", null, null,
                    "trace", System.currentTimeMillis() - 1, new CancellationToken()), sink());
        }});
    }

    private static ProviderSseParser parser() {
        return new ProviderSseParser(256, 1024, 20, 4096L);
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ProviderSseListener sink() {
        return new ProviderSseListener() {
            public boolean onEvent(ProviderSseEvent event) { return true; }
        };
    }

    private static InvocationContext context() {
        return new InvocationContext("tenant", null, null, "trace",
                System.currentTimeMillis() + 5000, new CancellationToken());
    }

    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException exception) { assertEquals(code, exception.getError().getCode()); }
    }
}
