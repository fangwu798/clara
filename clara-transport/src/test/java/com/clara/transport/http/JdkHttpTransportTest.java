package com.clara.transport.http;

import com.clara.exception.HttpTransportException;
import com.clara.core.transport.HttpTransportPolicy;
import com.clara.core.transport.IExecutionGuard;
import com.clara.core.transport.IHttpCall;
import com.clara.core.transport.RawHttpRequest;
import com.clara.core.transport.RawHttpResponse;
import com.clara.core.transport.sse.ISseEventListener;
import com.clara.core.transport.sse.SseEvent;
import com.clara.core.transport.sse.SseParserConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Behavior tests for the shared JDK HTTP transport.
 */
public class JdkHttpTransportTest {

    private HttpServer server;

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    public void synchronousResponseRead() throws Exception {
        URI endpoint = serve(200, "text/plain", "hello");
        RawHttpResponse response = call(endpoint, null, null, policy(1024, 1024));

        assertEquals(200, response.getStatusCode());
        assertEquals("hello", body(response));
        assertEquals("text/plain", header(response, "Content-Type"));
    }

    @Test
    public void non2xxResponseReturnsErrorBody() throws Exception {
        URI endpoint = serve(500, "text/plain", "boom");
        RawHttpResponse response = call(endpoint, null, null, policy(1024, 1024));

        assertEquals(500, response.getStatusCode());
        assertEquals("boom", body(response));
    }

    @Test
    public void sseResponseInvokesListener() throws Exception {
        URI endpoint = serve(200, "text/event-stream; charset=utf-8", "data: one\n\ndata: two\n\n");
        final List<SseEvent> events = new ArrayList<>();
        final int[] status = new int[1];

        RawHttpResponse response = call(endpoint, null, new ISseEventListener() {
            public void onStart(int value, Map<String, List<String>> headers) {
                status[0] = value;
            }

            public boolean onEvent(SseEvent event) {
                events.add(event);
                return true;
            }
        }, policy(1024, 1024));

        assertEquals(200, status[0]);
        assertEquals(2, events.size());
        assertEquals("one", events.get(0).getData());
        assertEquals("two", events.get(1).getData());
        assertEquals(0, response.getBody().length);
    }

    @Test
    public void nonSseSuccessWithListenerReturnsBody() throws Exception {
        URI endpoint = serve(200, "application/json", "{}");
        final List<SseEvent> events = new ArrayList<>();

        RawHttpResponse response = call(endpoint, null, new ISseEventListener() {
            public void onStart(int status, Map<String, List<String>> headers) {
            }

            public boolean onEvent(SseEvent event) {
                events.add(event);
                return true;
            }
        }, policy(1024, 1024));

        assertEquals("{}", body(response));
        assertTrue(events.isEmpty());
    }

    @Test
    public void endpointPolicyRejectsForeignHost() throws Exception {
        URI endpoint = serve(200, "text/plain", "hello");
        HttpTransportPolicy policy = new HttpTransportPolicy(
                new HostEndpointPolicy(Collections.singleton("example.com"), true),
                2000, 2000, 1024, 1024, Collections.<String, String>emptyMap(),
                sseConfig(1024));
        try {
            call(endpoint, null, null, policy);
            fail("expected endpoint denial");
        } catch (HttpTransportException expected) {
            assertEquals(HttpTransportException.Kind.ENDPOINT_DENIED, expected.getKind());
        }
    }

    @Test
    public void oversizedRequestRejectedBeforeSending() throws Exception {
        URI endpoint = serve(200, "text/plain", "hello");
        HttpTransportPolicy limit = policy(2, 1024);
        HttpTransportException expected = assertThrows(HttpTransportException.class,
                () -> call(endpoint, "abc", null, limit));
        assertEquals(HttpTransportException.Kind.REQUEST_TOO_LARGE, expected.getKind());
    }

    @Test
    public void oversizedResponseRejected() throws Exception {
        URI endpoint = serve(200, "text/plain", "0123456789");
        HttpTransportPolicy limit = policy(1024, 4);
        HttpTransportException expected = assertThrows(HttpTransportException.class,
                () -> call(endpoint, null, null, limit));
        assertEquals(HttpTransportException.Kind.RESPONSE_TOO_LARGE, expected.getKind());
    }

    @Test
    public void cancelledGuardRejectedBeforeConnecting() throws Exception {
        URI endpoint = serve(200, "text/plain", "hello");
        IExecutionGuard cancelled = new IExecutionGuard() {
            public boolean isCancelled() {
                return true;
            }

            public long remainingMillis() {
                return 1000L;
            }
        };
        JdkHttpTransport transport = new JdkHttpTransport();
        RawHttpRequest rawRequest = request(endpoint, null);
        HttpTransportPolicy limit = policy(1024, 1024);
        IHttpCall httpCall = transport.newCall(rawRequest, limit, cancelled);
        HttpTransportException expected = assertThrows(HttpTransportException.class, httpCall::execute);
        assertEquals(HttpTransportException.Kind.CANCELLED, expected.getKind());
    }

    private RawHttpResponse call(URI endpoint, String body, ISseEventListener listener,
            HttpTransportPolicy policy) {
        IHttpCall call = new JdkHttpTransport().newCall(request(endpoint, body), policy,
                IExecutionGuard.NONE);
        return listener == null ? call.execute() : call.execute(listener);
    }

    private static RawHttpRequest request(URI endpoint, String body) {
        byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        return new RawHttpRequest("POST", endpoint,
                Collections.singletonMap("Content-Type", "application/json"), payload);
    }

    private static HttpTransportPolicy policy(int maxRequestBytes, int maxResponseBytes) {
        return new HttpTransportPolicy(
                new HostEndpointPolicy(Collections.singleton("127.0.0.1"), true),
                2000, 2000, maxRequestBytes, maxResponseBytes,
                Collections.<String, String>emptyMap(), sseConfig(maxResponseBytes));
    }

    private static SseParserConfig sseConfig(int maxResponseBytes) {
        int limit = Math.min(1024, maxResponseBytes);
        return new SseParserConfig(limit, limit, 100, maxResponseBytes, true);
    }

    private URI serve(final int status, final String contentType, final String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/test", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                if (contentType != null) {
                    exchange.getResponseHeaders().add("Content-Type", contentType);
                }
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, payload.length);
                OutputStream output = exchange.getResponseBody();
                output.write(payload);
                output.close();
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/test");
    }

    private static String body(RawHttpResponse response) {
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

    private static String header(RawHttpResponse response, String name) {
        for (Map.Entry<String, List<String>> entry : response.getHeaders().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                    && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }
}
