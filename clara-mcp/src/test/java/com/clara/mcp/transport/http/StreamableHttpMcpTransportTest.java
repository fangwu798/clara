package com.clara.mcp.transport.http;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.mcp.client.JsonRpcNotification;
import com.clara.mcp.client.JsonRpcRequest;
import com.clara.mcp.client.JsonRpcResponse;
import com.clara.mcp.transport.JacksonMcpJsonCodec;
import com.clara.mcp.transport.McpTransportListener;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StreamableHttpMcpTransportTest {
    private HttpServer server;
    private StreamableHttpMcpTransport transport;

    @After
    public void tearDown() {
        if (transport != null) transport.close();
        if (server != null) server.stop(0);
    }

    @Test
    public void postsJsonAndReusesSessionWithDynamicHeaders() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> secondSession = new AtomicReference<String>();
        final AtomicReference<String> secondAuthorization = new AtomicReference<String>();
        startServer(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                int call = calls.incrementAndGet();
                if (call == 2) {
                    secondSession.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                    secondAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (call == 1) exchange.getResponseHeaders().set("Mcp-Session-Id", "session-1");
                respond(exchange, 200, response(String.valueOf(call)));
            }
        });
        final AtomicInteger tokens = new AtomicInteger();
        transport = builder().headersProvider(new McpHeadersProvider() {
            public Map<String, String> getHeaders() {
                return Collections.singletonMap("Authorization", "Bearer " + tokens.incrementAndGet());
            }
        }).build();
        transport.start(new RecordingListener());

        assertEquals(Boolean.TRUE, request("1", 1000L).getResult().get("ok"));
        assertEquals(Boolean.TRUE, request("2", 1000L).getResult().get("ok"));
        assertEquals("session-1", transport.getSessionId());
        assertEquals("session-1", secondSession.get());
        assertEquals("Bearer 2", secondAuthorization.get());
    }

    @Test
    public void acceptsSseNotificationsBeforeMatchingResponse() throws Exception {
        startServer(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                String body = "data: {\"jsonrpc\":\"2.0\",\"method\":\"progress\",\"params\":{\"step\":1}}\n\n"
                        + "data: " + response("sse") + "\n\n";
                respond(exchange, 200, body);
            }
        });
        RecordingListener listener = new RecordingListener();
        transport = builder().build(); transport.start(listener);

        assertEquals(Boolean.TRUE, request("sse", 1000L).getResult().get("ok"));
        assertEquals("progress", listener.notification.get().getMethod());
        assertNull(listener.failure.get());
    }

    @Test
    public void acceptsEmpty202ForNotificationAndCapturesSession() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch notificationReceived = new CountDownLatch(1);
        final AtomicReference<String> requestSession = new AtomicReference<String>();
        startServer(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                if (calls.incrementAndGet() == 1) {
                    exchange.getResponseHeaders().set("Mcp-Session-Id", "notification-session");
                    exchange.sendResponseHeaders(202, -1L); exchange.close();
                    notificationReceived.countDown(); return;
                }
                requestSession.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                respond(exchange, 200, response("after-notification"));
            }
        });
        RecordingListener listener = new RecordingListener();
        transport = builder().build(); transport.start(listener);

        transport.sendNotification(new JsonRpcNotification("notifications/initialized",
                Collections.<String, Object>emptyMap()));
        assertTrue(notificationReceived.await(1L, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, request("after-notification", 1000L).getResult().get("ok"));
        assertEquals("notification-session", requestSession.get());
        assertNull(listener.failure.get());
    }

    @Test
    public void mapsOversizedSseEventToStableError() throws Exception {
        startServer(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                respond(exchange, 200, "data: " + response("large") + "                       \n\n");
            }
        });
        transport = builder().maxResponseBytes(48).build(); transport.start(new RecordingListener());
        assertError(requestFuture("large", 1000L), ErrorCodes.MCP_MESSAGE_TOO_LARGE);
    }

    @Test
    public void listenerFailureDoesNotAbortSseResponse() throws Exception {
        startServer(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                respond(exchange, 200, "data: {\"jsonrpc\":\"2.0\",\"method\":\"progress\",\"params\":{}}\n\n"
                        + "data: " + response("safe") + "\n\n");
            }
        });
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        transport = builder().build();
        transport.start(new McpTransportListener() {
            public void onNotification(JsonRpcNotification value) { throw new IllegalStateException("listener"); }
            public void onServerRequest(JsonRpcRequest value) { }
            public void onFailure(Throwable value) { failure.set(value); }
        });

        assertEquals(Boolean.TRUE, request("safe", 1000L).getResult().get("ok"));
        assertEquals("listener", failure.get().getMessage());
    }

    @Test
    public void enforcesTimeoutCancellationAndResponseLimit() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        startServer(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                entered.countDown();
                try { Thread.sleep(200L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                respond(exchange, 200, response("slow") + "                                                            ");
            }
        });
        transport = builder().maxResponseBytes(48).build(); transport.start(new RecordingListener());

        assertError(requestFuture("slow", 30L), ErrorCodes.MCP_REQUEST_TIMEOUT);
        CompletableFuture<JsonRpcResponse> cancelled = requestFuture("cancel", 1000L);
        assertTrue(entered.await(1L, TimeUnit.SECONDS));
        assertTrue(transport.cancel("cancel"));
        assertTrue(cancelled.isCancelled());
        assertFalse(transport.cancel("cancel"));

        assertError(requestFuture("slow", 1000L), ErrorCodes.MCP_MESSAGE_TOO_LARGE);
    }

    @Test
    public void reconnectsEventStreamWithLastEventId() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> resumedFrom = new AtomicReference<String>();
        final CountDownLatch notifications = new CountDownLatch(2);
        startServer(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                int call = calls.incrementAndGet();
                if (call > 1) resumedFrom.compareAndSet(null,
                        exchange.getRequestHeaders().getFirst("Last-Event-ID"));
                String prefix = call == 1 ? "id: event-1\nretry: 1\n" : "";
                respond(exchange, 200, prefix
                        + "data: {\"jsonrpc\":\"2.0\",\"method\":\"tick\",\"params\":{}}\n\n");
            }
        });
        transport = builder().build();
        transport.start(new RecordingListener() {
            public void onNotification(JsonRpcNotification value) { notifications.countDown(); }
        });
        transport.startEventStream();

        assertTrue(notifications.await(2L, TimeUnit.SECONDS));
        assertEquals("event-1", resumedFrom.get());
    }

    @Test
    public void rejectsUnsafeEndpointsAndHeaders() throws Exception {
        try {
            StreamableHttpMcpTransport.builder().endpoint(new URI("http://localhost/mcp"))
                    .allowedHosts(Collections.singleton("localhost"))
                    .codec(new JacksonMcpJsonCodec()).build();
            fail("expected HTTP rejection");
        } catch (IllegalArgumentException expected) { }
        try {
            StreamableHttpMcpTransport.builder().endpoint(new URI("https://example.com/mcp"))
                    .allowedHosts(Collections.singleton("localhost"))
                    .codec(new JacksonMcpJsonCodec()).build();
            fail("expected host rejection");
        } catch (IllegalArgumentException expected) { }

        startServer(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException { respond(exchange, 200, response("header")); }
        });
        transport = builder().headersProvider(new McpHeadersProvider() {
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<String, String>();
                headers.put("Host", "other.example"); return headers;
            }
        }).build(); transport.start(new RecordingListener());
        assertError(requestFuture("header", 1000L), null);
    }

    private void startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/mcp", handler); server.start();
    }

    private StreamableHttpMcpTransport.Builder builder() throws Exception {
        return StreamableHttpMcpTransport.builder()
                .endpoint(new URI("http://localhost:" + server.getAddress().getPort() + "/mcp"))
                .allowedHosts(Collections.singleton("localhost")).allowHttp(true)
                .connectTimeoutMillis(1000).codec(new JacksonMcpJsonCodec());
    }

    private JsonRpcResponse request(String id, long timeoutMillis) throws Exception {
        return requestFuture(id, timeoutMillis).get(2L, TimeUnit.SECONDS);
    }

    private CompletableFuture<JsonRpcResponse> requestFuture(String id, long timeoutMillis) {
        return transport.sendRequest(new JsonRpcRequest(id, "tools/list",
                Collections.<String, Object>emptyMap()), timeoutMillis);
    }

    private static String response(String id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"ok\":true}}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void assertError(CompletableFuture<?> future, String expectedCode) throws Exception {
        try {
            future.get(2L, TimeUnit.SECONDS); fail("expected request failure");
        } catch (CancellationException exception) {
            if (expectedCode != null) fail("expected ClaraException");
        } catch (ExecutionException exception) {
            assertTrue(exception.getCause() instanceof ClaraException || expectedCode == null);
            if (expectedCode != null) assertEquals(expectedCode,
                    ((ClaraException) exception.getCause()).getError().getCode());
        }
    }

    private static class RecordingListener implements McpTransportListener {
        private final AtomicReference<JsonRpcNotification> notification =
                new AtomicReference<JsonRpcNotification>();
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        public void onNotification(JsonRpcNotification value) { notification.set(value); }
        public void onServerRequest(JsonRpcRequest value) { }
        public void onFailure(Throwable value) { failure.compareAndSet(null, value); }
    }
}
