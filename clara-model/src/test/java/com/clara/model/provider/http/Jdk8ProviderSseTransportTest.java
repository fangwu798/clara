package com.clara.model.provider.http;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class Jdk8ProviderSseTransportTest {
    private HttpServer server;
    private String origin;

    @Before public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream", stream(
                ": heartbeat\n\ndata: first\r\n\r\ndata: [DONE]\n\n",
                "Text/Event-Stream; charset=utf-8"));
        server.createContext("/wrong-type", stream("data: x\n\n", "application/json"));
        server.createContext("/large-stream", stream("data: 12345678901234567890\n\n",
                "text/event-stream"));
        server.createContext("/error", fixed(429, "provider diagnostic", "application/json"));
        server.createContext("/large-error", fixed(500, "12345678901234567890",
                "application/json"));
        server.createContext("/redirect", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Location", "/stream");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
        });
        server.createContext("/slow", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try { Thread.sleep(300L); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                try { exchange.getResponseBody().write("data: late\n\n".getBytes(StandardCharsets.UTF_8)); }
                catch (IOException ignored) { }
                exchange.close();
            }
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After public void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test public void streamsEventsAndReturnsResponseMetadata() throws Exception {
        final List<String> events = new ArrayList<String>();
        ProviderHttpResponse response = transport(100, 1024, 256, 512, 20, 1000)
                .execute(request("/stream", "request"), context(), new ProviderSseListener() {
                    public boolean onEvent(ProviderSseEvent event) {
                        events.add(event.getData());
                        return true;
                    }
                });

        assertEquals(200, response.getStatusCode());
        assertEquals(0, response.getBody().length);
        assertEquals(2, events.size());
        assertEquals("first", events.get(0));
        assertEquals("[DONE]", events.get(1));
    }

    @Test public void listenerCanStopWithoutReadingRemainingEvents() throws Exception {
        final List<String> events = new ArrayList<String>();
        ProviderHttpResponse response = transport(100, 1024, 256, 512, 20, 1000)
                .execute(request("/stream", null), context(), new ProviderSseListener() {
                    public boolean onEvent(ProviderSseEvent event) {
                        events.add(event.getData());
                        return false;
                    }
                });

        assertEquals(200, response.getStatusCode());
        assertEquals(Collections.singletonList("first"), events);
    }

    @Test public void returnsHttpFailuresAndDoesNotFollowRedirects() throws Exception {
        Jdk8ProviderSseTransport transport = transport(100, 100, 50, 80, 10, 1000);
        ProviderHttpResponse error = transport.execute(request("/error", null), context(), sink());
        assertEquals(429, error.getStatusCode());
        assertEquals("provider diagnostic", new String(error.getBody(), StandardCharsets.UTF_8));

        ProviderHttpResponse redirect = transport.execute(request("/redirect", null), context(), sink());
        assertEquals(302, redirect.getStatusCode());
        assertEquals(0, redirect.getBody().length);
    }

    @Test public void rejectsWrongContentTypeAndOversizedResponses() {
        final Jdk8ProviderSseTransport normal = transport(100, 100, 50, 80, 10, 1000);
        assertCode(ErrorCodes.MODEL_PROTOCOL_ERROR, new Runnable() { public void run() {
            normal.execute(request("/wrong-type", null), context(), sink());
        }});
        final Jdk8ProviderSseTransport streamLimited = transport(100, 16, 8, 16, 10, 1000);
        assertCode(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, new Runnable() { public void run() {
            streamLimited.execute(request("/large-stream", null), context(), sink());
        }});
        final Jdk8ProviderSseTransport errorLimited = transport(100, 10, 10, 10, 10, 1000);
        assertCode(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, new Runnable() { public void run() {
            errorLimited.execute(request("/large-error", null), context(), sink());
        }});
    }

    @Test public void enforcesOriginRequestLimitDeadlineAndReadTimeout() {
        final Jdk8ProviderSseTransport limited = transport(2, 100, 50, 80, 10, 1000);
        assertCode(ErrorCodes.MODEL_REQUEST_TOO_LARGE, new Runnable() { public void run() {
            limited.execute(request("/stream", "abc"), context(), sink());
        }});
        assertCode(ErrorCodes.MODEL_ENDPOINT_DENIED, new Runnable() { public void run() {
            try {
                limited.execute(new ProviderHttpRequest("GET",
                        new URI("http://localhost:" + server.getAddress().getPort() + "/stream"),
                        null, null), context(), sink());
            } catch (java.net.URISyntaxException exception) { throw new AssertionError(exception); }
        }});
        final InvocationContext expired = new InvocationContext("tenant", null, null, "trace",
                System.currentTimeMillis() - 1L, new CancellationToken());
        assertCode(ErrorCodes.MODEL_REQUEST_TIMEOUT, new Runnable() { public void run() {
            limited.execute(request("/stream", null), expired, sink());
        }});
        final Jdk8ProviderSseTransport shortRead = transport(100, 100, 50, 80, 10, 50);
        assertCode(ErrorCodes.MODEL_REQUEST_TIMEOUT, new Runnable() { public void run() {
            shortRead.execute(request("/slow", null), context(), sink());
        }});
    }

    private Jdk8ProviderSseTransport transport(int maxRequest, int maxResponse,
            int maxLine, int maxEvent, int maxEvents, int readTimeout) {
        Jdk8ProviderHttpTransportConfig config = new Jdk8ProviderHttpTransportConfig(
                Collections.singleton(origin), 1000, readTimeout, maxRequest, maxResponse, true);
        return new Jdk8ProviderSseTransport(config, maxLine, maxEvent, maxEvents);
    }

    private ProviderHttpRequest request(String path, String body) {
        try {
            return new ProviderHttpRequest(body == null ? "GET" : "POST", new URI(origin + path),
                    Collections.singletonMap("Accept", "text/event-stream"),
                    body == null ? null : body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) { throw new AssertionError(exception); }
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

    private static HttpHandler stream(final String body, final String contentType) {
        return fixed(200, body, contentType);
    }

    private static HttpHandler fixed(final int status, final String body,
            final String contentType) {
        return new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.getResponseHeaders().add("X-Provider-Test", "yes");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        };
    }

    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException exception) { assertEquals(code, exception.getError().getCode()); }
    }
}
