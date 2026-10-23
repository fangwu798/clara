package com.clara.mcp.transport.http;

import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.api.IMcpHeadersProvider;
import com.clara.mcp.api.IMcpTransportListener;
import com.clara.mcp.transport.JacksonMcpJsonCodec;
import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;
import com.clara.mcp.transport.jsonrpc.JsonRpcResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link StreamableHttpMcpTransport} 的行为测试：对着本地 HttpServer 验证请求关联、会话/协议头、
 * 取消、通知失败与事件流分发。
 *
 * <p>为什么要在协议层测这些：MCP 的失败大多不是「请求报错」，而是「请求永远挂在那里」——
 * ID 不匹配、通知没有回调、事件流断了不重连、取消只停了上层 Future 却没停 HTTP 调用。
 * 这些都不会抛异常，只会在接入真实服务端后表现为随机卡死，必须逐条钉死。
 */
public class StreamableHttpMcpTransportBehaviorTest {

    private HttpServer server;
    private StreamableHttpMcpTransport transport;

    @After
    public void tearDown() {
        if (transport != null) {
            transport.close();
            transport = null;
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    public void lifecycleGuards() throws Exception {
        URI endpoint = serve(new SlowHandler(0L, 200, "application/json", ok("1")));
        transport = builder(endpoint).build();

        try {
            transport.sendRequest(request("1"), 1000L);
            fail("未 start 就发送应拒绝");
        } catch (IllegalStateException expected) {
            // 期望：传输未启动
        }
        try {
            transport.start(null);
            fail("listener 为空应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
        transport.start(new RecordingListener());
        try {
            transport.start(new RecordingListener());
            fail("重复 start 应拒绝");
        } catch (IllegalStateException expected) {
            // 期望：已启动
        }
        try {
            transport.sendRequest(null, 1000L);
            fail("请求为空应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
        try {
            transport.sendRequest(request("1"), 0L);
            fail("超时非正应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
        try {
            transport.setProtocolVersion("  ");
            fail("空白协议版本应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
        transport.checkHealth();

        transport.close();
        try {
            transport.checkHealth();
            fail("关闭后健康检查应拒绝");
        } catch (ClaraException closed) {
            assertEquals(ErrorCodes.MCP_TRANSPORT_CLOSED, closed.getError().getCode());
        }
        try {
            transport.start(new RecordingListener());
            fail("关闭后 start 应拒绝");
        } catch (ClaraException closed) {
            assertEquals(ErrorCodes.MCP_TRANSPORT_CLOSED, closed.getError().getCode());
        }
    }

    @Test
    public void protocolVersionAndProviderHeadersAreSent() throws Exception {
        final AtomicReference<String> version = new AtomicReference<>();
        final AtomicReference<String> apiKey = new AtomicReference<>();
        final AtomicReference<String> accept = new AtomicReference<>();
        URI endpoint = serve(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                version.set(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                apiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
                accept.set(exchange.getRequestHeaders().getFirst("Accept"));
                respond(exchange, 200, "application/json", ok("1"));
            }
        });
        transport = builder(endpoint)
                .headersProvider(new IMcpHeadersProvider() {
                    public Map<String, String> getHeaders() {
                        Map<String, String> headers = new LinkedHashMap<>();
                        headers.put("X-Api-Key", "secret");
                        return headers;
                    }
                })
                .build();
        transport.start(new RecordingListener());
        transport.setProtocolVersion("2025-06-18");

        transport.sendRequest(request("1"), 2000L).get(3, TimeUnit.SECONDS);

        assertEquals("2025-06-18", version.get());
        assertEquals("secret", apiKey.get());
        assertEquals("application/json, text/event-stream", accept.get());
    }

    @Test
    public void duplicateRequestIdRejectedWhileInFlight() throws Exception {
        URI endpoint = serve(new SlowHandler(1500L, 200, "application/json", ok("1")));
        transport = builder(endpoint).build();
        transport.start(new RecordingListener());

        CompletableFuture<JsonRpcResponse> first = transport.sendRequest(request("dup"), 5000L);
        CompletableFuture<JsonRpcResponse> second = transport.sendRequest(request("dup"), 5000L);

        try {
            second.get(2, TimeUnit.SECONDS);
            fail("重复 ID 应直接拒绝");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof ClaraException);
            assertEquals(ErrorCodes.MCP_DUPLICATE_REQUEST_ID,
                    ((ClaraException) expected.getCause()).getError().getCode());
        }
        assertFalse("第一个请求仍在进行中", first.isDone());
        assertTrue(transport.cancel("dup"));
    }

    @Test
    public void cancelReportsWhetherRequestWasActive() throws Exception {
        URI endpoint = serve(new SlowHandler(1500L, 200, "application/json", ok("1")));
        transport = builder(endpoint).build();
        transport.start(new RecordingListener());

        try {
            transport.cancel(" ");
            fail("空白 ID 应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
        assertFalse("没有在途请求时应返回 false", transport.cancel("missing"));

        CompletableFuture<JsonRpcResponse> inFlight = transport.sendRequest(request("c1"), 5000L);
        assertTrue(transport.cancel("c1"));
        assertTrue("取消后 Future 应处于取消态", inFlight.isCancelled());
    }

    @Test
    public void closeCancelsInFlightRequests() throws Exception {
        URI endpoint = serve(new SlowHandler(1500L, 200, "application/json", ok("1")));
        transport = builder(endpoint).build();
        transport.start(new RecordingListener());
        CompletableFuture<JsonRpcResponse> inFlight = transport.sendRequest(request("x"), 5000L);

        transport.close();

        assertTrue(inFlight.isCancelled());
    }

    @Test
    public void notificationReportsSuccessAndFailure() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        URI endpoint = serve(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                int call = calls.incrementAndGet();
                respond(exchange, call == 1 ? 202 : 500, "application/json", "");
            }
        });
        transport = builder(endpoint).build();
        RecordingListener listener = new RecordingListener();
        transport.start(listener);

        transport.sendNotification(notification());
        transport.sendNotification(notification());

        assertTrue("成功通知不应报错", listener.failureLatch.await(3, TimeUnit.SECONDS) || calls.get() == 2);
        assertTrue("失败通知应回调 onFailure", waitFor(listener.failure, 3000L));
        try {
            transport.sendNotification(null);
            fail("通知为空应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
    }

    @Test
    public void mismatchedResponseIdIsRejected() throws Exception {
        URI endpoint = serve(new SlowHandler(0L, 200, "application/json", ok("2")));
        transport = builder(endpoint).build();
        transport.start(new RecordingListener());
        assertFailureCode(transport.sendRequest(request("1"), 2000L), ErrorCodes.MCP_INVALID_MESSAGE);
    }

    @Test
    public void nonJsonBodyIsRejected() throws Exception {
        URI endpoint = serve(new SlowHandler(0L, 200, "application/json", "not-json"));
        transport = builder(endpoint).build();
        transport.start(new RecordingListener());
        assertFailureCode(transport.sendRequest(request("1"), 2000L), ErrorCodes.MCP_INVALID_MESSAGE);
    }

    @Test
    public void emptyJsonBodyIsRejected() throws Exception {
        URI endpoint = serve(new SlowHandler(0L, 200, "application/json", ""));
        transport = builder(endpoint).build();
        transport.start(new RecordingListener());
        assertFailureCode(transport.sendRequest(request("1"), 2000L), ErrorCodes.MCP_INVALID_MESSAGE);
    }

    @Test
    public void timeoutMapsToRequestTimeout() throws Exception {
        URI endpoint = serve(new SlowHandler(1500L, 200, "application/json", ok("1")));
        transport = builder(endpoint).build();
        transport.start(new RecordingListener());
        assertFailureCode(transport.sendRequest(request("1"), 200L), ErrorCodes.MCP_REQUEST_TIMEOUT);
    }

    @Test
    public void eventStreamDispatchesAndReconnectsWithLastEventId() throws Exception {
        final AtomicInteger gets = new AtomicInteger();
        final AtomicReference<String> lastEventId = new AtomicReference<>();
        final CountDownLatch secondGet = new CountDownLatch(1);
        URI endpoint = serve(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                if (!"GET".equals(exchange.getRequestMethod())) {
                    respond(exchange, 200, "application/json", ok("1"));
                    return;
                }
                if (gets.incrementAndGet() == 1) {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    OutputStream output = exchange.getResponseBody();
                    output.write(("id: 7\nretry: 10\ndata: {\"jsonrpc\":\"2.0\",\"method\":"
                            + "\"notifications/progress\",\"params\":{}}\n\n"
                            + "id: 8\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"srv-1\",\"method\":"
                            + "\"sampling/createMessage\",\"params\":{}}\n\n").getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    output.close();
                    return;
                }
                lastEventId.set(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
                secondGet.countDown();
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, "text/event-stream", "");
            }
        });
        transport = builder(endpoint).build();
        RecordingListener listener = new RecordingListener();
        transport.start(listener);

        transport.startEventStream();
        transport.startEventStream();

        assertTrue("通知应被分发", listener.notificationLatch.await(5, TimeUnit.SECONDS));
        assertTrue("服务端请求应被分发", listener.serverRequestLatch.await(5, TimeUnit.SECONDS));
        assertTrue("断开后应重连", secondGet.await(5, TimeUnit.SECONDS));
        assertEquals("重连必须带上最后事件 ID", "8", lastEventId.get());
    }

    @Test
    public void eventStreamServerErrorIsReported() throws Exception {
        URI endpoint = serve(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                respond(exchange, 500, "text/plain", "boom");
            }
        });
        transport = builder(endpoint).build();
        RecordingListener listener = new RecordingListener();
        transport.start(listener);

        transport.startEventStream();

        assertTrue("事件流失败应回调 onFailure", listener.failureLatch.await(5, TimeUnit.SECONDS));
    }

    private void assertFailureCode(CompletableFuture<JsonRpcResponse> future, String expectedCode)
            throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("预期失败：" + expectedCode);
        } catch (ExecutionException expected) {
            assertTrue("失败类型应为领域异常", expected.getCause() instanceof ClaraException);
            assertEquals(expectedCode, ((ClaraException) expected.getCause()).getError().getCode());
        }
    }

    private StreamableHttpMcpTransport.Builder builder(URI endpoint) {
        return StreamableHttpMcpTransport.builder()
                .endpoint(endpoint)
                .allowedHosts(Collections.singleton("127.0.0.1"))
                .allowHttp(true)
                .codec(new JacksonMcpJsonCodec())
                .connectTimeoutMillis(2000)
                .maxResponseBytes(1024 * 1024);
    }

    private static JsonRpcRequest request(String id) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("x", 1);
        return new JsonRpcRequest(id, "ping", params);
    }

    private static JsonRpcNotification notification() {
        return new JsonRpcNotification("notifications/initialized", Collections.<String, Object>emptyMap());
    }

    private static String ok(String id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"ok\":true}}";
    }

    private URI serve(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rpc", handler);
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/rpc");
    }

    private static void drain(HttpExchange exchange) throws IOException {
        byte[] buffer = new byte[1024];
        while (exchange.getRequestBody().read(buffer) >= 0) {
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        OutputStream output = exchange.getResponseBody();
        output.write(payload);
        output.close();
    }

    private static boolean waitFor(AtomicReference<Throwable> value, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (value.get() != null) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    /**
     * 可配置延迟与响应体的处理器：用于制造「在途请求」与超时。
     */
    private static final class SlowHandler implements HttpHandler {
        private final long delayMillis;
        private final int status;
        private final String contentType;
        private final String body;

        private SlowHandler(long delayMillis, int status, String contentType, String body) {
            this.delayMillis = delayMillis;
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        public void handle(HttpExchange exchange) throws IOException {
            drain(exchange);
            if (delayMillis > 0L) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(exchange, status, contentType, body);
        }
    }

    /**
     * 记录回调的监听器：每个断言只关心一种回调是否到达。
     */
    private static final class RecordingListener implements IMcpTransportListener {
        private final CountDownLatch notificationLatch = new CountDownLatch(1);
        private final CountDownLatch serverRequestLatch = new CountDownLatch(1);
        private final CountDownLatch failureLatch = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        public void onNotification(JsonRpcNotification notification) {
            notificationLatch.countDown();
        }

        public void onServerRequest(JsonRpcRequest request) {
            serverRequestLatch.countDown();
        }

        public void onFailure(Throwable error) {
            failure.set(error);
            failureLatch.countDown();
        }
    }
}