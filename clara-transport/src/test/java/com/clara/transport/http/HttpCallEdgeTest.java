package com.clara.transport.http;

import com.clara.core.transport.HttpTransportPolicy;
import com.clara.core.transport.IExecutionGuard;
import com.clara.core.transport.IHttpCall;
import com.clara.core.transport.RawHttpRequest;
import com.clara.core.transport.sse.ISseEventListener;
import com.clara.core.transport.sse.SseEvent;
import com.clara.core.transport.sse.SseParserConfig;
import com.clara.enums.HttpTransportErrorKindEnum;
import com.clara.exception.HttpTransportException;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link HttpCall} 的生命周期与失败路径：一次性语义、取消、截止时间与 IO 错误归类。
 *
 * <p>这些路径决定调用方能否正确区分「用户取消」「超时」「网络失败」，也防止同一请求被重复发送。
 */
public class HttpCallEdgeTest {

    private HttpServer server;

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    public void listenerIsRequiredForStreamingExecution() {
        IHttpCall call = new JdkHttpTransport().newCall(request(URI.create("http://127.0.0.1:1/x"), null),
                policy(), IExecutionGuard.NONE);

        assertThrows(IllegalArgumentException.class, () -> call.execute((ISseEventListener) null));
    }

    @Test
    public void callIsSingleUse() throws Exception {
        URI endpoint = serve("pong");
        IHttpCall call = new JdkHttpTransport().newCall(request(endpoint, null), policy(), IExecutionGuard.NONE);

        assertEquals(200, call.execute().getStatusCode());
        IllegalStateException exception = assertThrows(IllegalStateException.class, call::execute);
        assertTrue(exception.getMessage().contains("single-use"));
    }

    @Test
    public void cancelBeforeExecuteFailsAsCancelled() {
        IHttpCall call = new JdkHttpTransport().newCall(request(URI.create("http://127.0.0.1:1/x"), null),
                policy(), IExecutionGuard.NONE);
        assertFalse(call.isCancelled());

        call.cancel();

        assertTrue(call.isCancelled());
        HttpTransportException exception = assertThrows(HttpTransportException.class, call::execute);
        assertEquals(HttpTransportErrorKindEnum.CANCELLED, exception.getKind());
    }

    @Test
    public void closeIsEquivalentToCancel() {
        IHttpCall call = new JdkHttpTransport().newCall(request(URI.create("http://127.0.0.1:1/x"), null),
                policy(), IExecutionGuard.NONE);

        call.close();

        assertTrue(call.isCancelled());
    }

    @Test
    public void deadlineExpiringDuringSetupBecomesTimeout() {
        AtomicInteger calls = new AtomicInteger();
        IExecutionGuard expiring = new IExecutionGuard() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public long remainingMillis() {
                return calls.incrementAndGet() >= 3 ? 0L : 1000L;
            }
        };
        IHttpCall call = new JdkHttpTransport().newCall(
                request(URI.create("http://127.0.0.1:1/x"), null), policy(), expiring);

        HttpTransportException exception = assertThrows(HttpTransportException.class, call::execute);
        assertEquals(HttpTransportErrorKindEnum.TIMEOUT, exception.getKind());
    }

    @Test
    public void connectionFailureIsReportedAsIoError() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/refused");
        IHttpCall call = new JdkHttpTransport().newCall(request(endpoint, null), policy(), IExecutionGuard.NONE);

        HttpTransportException exception = assertThrows(HttpTransportException.class, call::execute);
        assertEquals(HttpTransportErrorKindEnum.IO_ERROR, exception.getKind());
    }

    @Test
    public void appliesPolicyAndRequestHeaders() throws Exception {
        final String[] seen = new String[2];
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/headers", exchange -> {
            seen[0] = exchange.getRequestHeaders().getFirst("X-Default");
            seen[1] = exchange.getRequestHeaders().getFirst("X-Custom");
            byte[] payload = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/headers");
        HttpTransportPolicy policy = new HttpTransportPolicy(
                new HostEndpointPolicy(Collections.singleton("127.0.0.1"), true),
                2000, 2000, 1024, 1024,
                Collections.singletonMap("X-Default", "default-value"), sseConfig());

        RawHttpRequest request = new RawHttpRequest("GET", endpoint,
                Collections.singletonMap("X-Custom", "custom-value"), null);
        new JdkHttpTransport().newCall(request, policy, IExecutionGuard.NONE).execute();

        assertEquals("default-value", seen[0]);
        assertEquals("custom-value", seen[1]);
    }

    private URI serve(String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/test", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/test");
    }

    private static RawHttpRequest request(URI endpoint, String body) {
        return new RawHttpRequest("POST", endpoint, Collections.<String, String>emptyMap(),
                body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpTransportPolicy policy() {
        return new HttpTransportPolicy(new HostEndpointPolicy(Collections.singleton("127.0.0.1"), true),
                2000, 2000, 1024, 1024, Collections.<String, String>emptyMap(), sseConfig());
    }

    private static SseParserConfig sseConfig() {
        return new SseParserConfig(1024, 1024, 100, 1024, true);
    }
}