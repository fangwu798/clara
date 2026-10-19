package com.clara.mcp.transport.http;

import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.api.IMcpTransportListener;
import com.clara.mcp.transport.JacksonMcpJsonCodec;
import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;
import com.clara.mcp.transport.jsonrpc.JsonRpcResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Integration tests for the MCP HTTP protocol adapter against a local server.
 */
public class StreamableHttpMcpTransportIntegrationTest {

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
    public void jsonResponseCompletesMatchingRequest() throws Exception {
        URI endpoint = serve(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                respond(exchange, 200, "application/json",
                        "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"ok\":true}}");
            }
        });
        start(endpoint);

        JsonRpcResponse response = transport.sendRequest(request("1"), 2000L)
                .get(3, TimeUnit.SECONDS);

        assertEquals("1", response.getId());
        assertEquals(Boolean.TRUE, response.getResult().get("ok"));
    }

    @Test
    public void sseResponseCompletesMatchingRequest() throws Exception {
        URI endpoint = serve(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                respond(exchange, 200, "text/event-stream",
                        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"ok\":true}}\n\n");
            }
        });
        start(endpoint);

        JsonRpcResponse response = transport.sendRequest(request("1"), 2000L)
                .get(3, TimeUnit.SECONDS);

        assertEquals("1", response.getId());
        assertEquals(Boolean.TRUE, response.getResult().get("ok"));
    }

    @Test
    public void sessionIdFromResponseIsSentOnNextRequest() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> secondSessionHeader = new AtomicReference<>();
        URI endpoint = serve(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                int call = calls.incrementAndGet();
                if (call == 2) {
                    secondSessionHeader.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                }
                exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
                respond(exchange, 200, "application/json",
                        "{\"jsonrpc\":\"2.0\",\"id\":\"" + call + "\",\"result\":{\"ok\":true}}");
            }
        });
        start(endpoint);

        transport.sendRequest(request("1"), 2000L).get(3, TimeUnit.SECONDS);
        transport.sendRequest(request("2"), 2000L).get(3, TimeUnit.SECONDS);

        assertEquals("session-1", secondSessionHeader.get());
    }

    @Test
    public void non2xxResponseFailsWithTransportError() throws Exception {
        URI endpoint = serve(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                respond(exchange, 403, "text/plain", "forbidden");
            }
        });
        start(endpoint);

        try {
            transport.sendRequest(request("1"), 2000L).get(3, TimeUnit.SECONDS);
            fail("expected transport failure");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof ClaraException);
            assertEquals(ErrorCodes.MCP_TRANSPORT_IO_ERROR,
                    ((ClaraException) expected.getCause()).getError().getCode());
        }
    }

    @Test
    public void emptySseResponseFailsAsTransportError() throws Exception {
        URI endpoint = serve(new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                respond(exchange, 200, "text/event-stream", "");
            }
        });
        start(endpoint);

        try {
            transport.sendRequest(request("1"), 2000L).get(3, TimeUnit.SECONDS);
            fail("expected transport failure");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof ClaraException);
            assertEquals(ErrorCodes.MCP_TRANSPORT_IO_ERROR,
                    ((ClaraException) expected.getCause()).getError().getCode());
        }
    }

    private void start(URI endpoint) {
        transport = StreamableHttpMcpTransport.builder()
                .endpoint(endpoint)
                .allowedHosts(Collections.singleton("127.0.0.1"))
                .allowHttp(true)
                .codec(new JacksonMcpJsonCodec())
                .connectTimeoutMillis(2000)
                .build();
        transport.start(new SilentListener());
    }

    private static JsonRpcRequest request(String id) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("x", 1);
        return new JsonRpcRequest(id, "ping", params);
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

    private static final class SilentListener implements IMcpTransportListener {
        public void onNotification(JsonRpcNotification notification) {
        }

        public void onServerRequest(JsonRpcRequest request) {
        }

        public void onFailure(Throwable failure) {
        }
    }
}
