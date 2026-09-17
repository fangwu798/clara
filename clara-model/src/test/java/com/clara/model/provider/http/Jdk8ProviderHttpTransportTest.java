package com.clara.model.provider.http;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class Jdk8ProviderHttpTransportTest {
    private HttpServer server;
    private String origin;

    @Before public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                byte[] request = read(exchange.getRequestBody());
                exchange.getResponseHeaders().add("X-Test", "yes");
                exchange.sendResponseHeaders(201, request.length);
                exchange.getResponseBody().write(request);
                exchange.close();
            }
        });
        server.createContext("/large", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                byte[] response = new byte[20];
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            }
        });
        server.createContext("/redirect", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Location", "/echo");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After public void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test public void postsBoundedBodyAndReturnsDefensiveResponse() throws Exception {
        Jdk8ProviderHttpTransport transport = transport(100, 100);
        Map<String, String> headers = Collections.singletonMap("Content-Type", "application/json");
        ProviderHttpResponse response = transport.execute(new ProviderHttpRequest("post",
                new URI(origin + "/echo"), headers, "hello".getBytes(StandardCharsets.UTF_8)), context());
        assertEquals(201, response.getStatusCode());
        assertEquals("hello", new String(response.getBody(), StandardCharsets.UTF_8));
        byte[] changed = response.getBody();
        changed[0] = 'X';
        assertEquals("hello", new String(response.getBody(), StandardCharsets.UTF_8));
    }

    @Test public void rejectsUnlistedOriginsAndOversizedTraffic() throws Exception {
        final Jdk8ProviderHttpTransport transport = transport(2, 10);
        assertCode(ErrorCodes.MODEL_ENDPOINT_DENIED, new Runnable() { public void run() {
            transport.execute(request("http://localhost:" + server.getAddress().getPort() + "/echo",
                    new byte[0]), context());
        }});
        assertCode(ErrorCodes.MODEL_REQUEST_TOO_LARGE, new Runnable() { public void run() {
            transport.execute(request(origin + "/echo", new byte[] {1, 2, 3}), context());
        }});
        final Jdk8ProviderHttpTransport responseLimited = transport(100, 10);
        assertCode(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, new Runnable() { public void run() {
            responseLimited.execute(request(origin + "/large", new byte[0]), context());
        }});
    }

    @Test public void doesNotFollowProviderRedirects() throws Exception {
        ProviderHttpResponse response = transport(100, 100).execute(
                new ProviderHttpRequest("GET", new URI(origin + "/redirect"), null, null), context());
        assertEquals(302, response.getStatusCode());
        assertEquals(0, response.getBody().length);
    }

    @Test public void enforcesDeadlineCancellationAndHeaderSafety() throws Exception {
        final Jdk8ProviderHttpTransport transport = transport(100, 100);
        final InvocationContext expired = new InvocationContext("tenant", null, null, "trace",
                System.currentTimeMillis() - 1, new CancellationToken());
        assertCode(ErrorCodes.MODEL_REQUEST_TIMEOUT, new Runnable() { public void run() {
            transport.execute(request(origin + "/echo", new byte[0]), expired);
        }});
        final CancellationToken token = new CancellationToken();
        token.cancel();
        final InvocationContext cancelled = new InvocationContext("tenant", null, null, "trace",
                System.currentTimeMillis() + 5000, token);
        assertCode(ErrorCodes.INVOCATION_CANCELLED, new Runnable() { public void run() {
            transport.execute(request(origin + "/echo", new byte[0]), cancelled);
        }});

        Map<String, String> injected = new LinkedHashMap<String, String>();
        injected.put("Authorization", "safe\r\nX-Evil: yes");
        try {
            new ProviderHttpRequest("POST", new URI(origin + "/echo"), injected, new byte[0]);
            fail("expected unsafe header rejection");
        } catch (IllegalArgumentException expected) { }

        try {
            new ProviderHttpTransportConfig(Collections.singleton(origin),
                    1000, 1000, 100, 100, false);
            fail("expected HTTP origin rejection");
        } catch (IllegalArgumentException expected) { }

        Map<String, String> duplicates = new LinkedHashMap<String, String>();
        duplicates.put("Authorization", "one");
        duplicates.put("authorization", "two");
        try {
            new ProviderHttpRequest("POST", new URI(origin + "/echo"), duplicates, new byte[0]);
            fail("expected duplicate header rejection");
        } catch (IllegalArgumentException expected) { }
    }

    private Jdk8ProviderHttpTransport transport(int maxRequest, int maxResponse) {
        return new Jdk8ProviderHttpTransport(new ProviderHttpTransportConfig(
                Collections.singleton(origin), 1000, 1000, maxRequest, maxResponse, true));
    }

    private static ProviderHttpRequest request(String endpoint, byte[] body) {
        try { return new ProviderHttpRequest("POST", new URI(endpoint), null, body); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static InvocationContext context() {
        return new InvocationContext("tenant", null, null, "trace",
                System.currentTimeMillis() + 5000, new CancellationToken());
    }

    private static byte[] read(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[64];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally { input.close(); }
    }

    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException exception) { assertEquals(code, exception.getError().getCode()); }
    }
}
