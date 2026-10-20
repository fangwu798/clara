package com.clara.model.embedding;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Embedding client behaviour against a local HTTP endpoint (no external network).
 */
public class HttpEmbeddingClientTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "{\"data\":[{\"embedding\":[0.25,0.5,-0.75]}]}";

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requestBody.set(read(exchange.getRequestBody()));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            }
        });
        server.start();
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void embedsTextAndSendsExpectedRequest() {
        HttpEmbeddingClient client = new HttpEmbeddingClient(endpoint(), "embed-model", "k-1");

        float[] vector = client.embed("hello");

        assertArrayEquals(new float[] {0.25f, 0.5f, -0.75f}, vector, 0.0001f);
        assertTrue(requestBody.get().contains("\"model\":\"embed-model\""));
        assertTrue(requestBody.get().contains("\"input\":\"hello\""));
        assertEquals("Bearer k-1", authorization.get());
    }

    @Test
    public void rejectsNullOrInvalidConstructorArguments() {
        assertIllegalArgument(() -> new HttpEmbeddingClient(null, "m", "k"));
        assertIllegalArgument(() -> new HttpEmbeddingClient(URI.create("/relative"), "m", "k"));
        assertIllegalArgument(() -> new HttpEmbeddingClient(URI.create("ftp://host/v1/embeddings"), "m", "k"));
        assertIllegalArgument(() -> new HttpEmbeddingClient(URI.create("http://"), "m", "k"));
        assertIllegalArgument(() -> new HttpEmbeddingClient(endpoint(), "  ", "k"));
        assertIllegalArgument(() -> new HttpEmbeddingClient(endpoint(), "m", ""));
        assertIllegalArgument(() -> new HttpEmbeddingClient(endpoint(), "m", "k", 0, 1));
        assertIllegalArgument(() -> new HttpEmbeddingClient(endpoint(), "m", "k", 1, 0));
        assertIllegalArgument(() -> new HttpEmbeddingClient(endpoint(), "m", "k").embed(null));
    }

    @Test
    public void failsOnNonSuccessfulStatus() {
        status = 500;
        responseBody = "boom";
        HttpEmbeddingClient client = new HttpEmbeddingClient(endpoint(), "m", "k");
        try {
            client.embed("x");
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("HTTP 500"));
            assertTrue(expected.getMessage().contains("boom"));
        }
    }

    @Test
    public void failsWhenResponseIsNotJson() {
        responseBody = "not-json";
        assertEmbedFails();
    }

    @Test
    public void failsWhenDataArrayIsMissingOrEmpty() {
        responseBody = "{\"data\":[]}";
        assertEmbedFails();
        responseBody = "{}";
        assertEmbedFails();
    }

    @Test
    public void failsWhenEmbeddingVectorIsMissingOrHasNonNumber() {
        responseBody = "{\"data\":[{}]}";
        assertEmbedFails();
        responseBody = "{\"data\":[{\"embedding\":[]}]}";
        assertEmbedFails();
        responseBody = "{\"data\":[{\"embedding\":[0.1,\"x\"]}]}";
        assertEmbedFails();
    }

    @Test
    public void wrapsTransportFailure() {
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;
        HttpEmbeddingClient client = new HttpEmbeddingClient(URI.create("http://127.0.0.1:" + port + "/v1/embeddings"),
                "m", "k");
        try {
            client.embed("x");
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
            assertTrue(expected.getMessage().startsWith("embedding "));
            assertNotNull(expected.getCause());
        }
    }

    @Test
    public void builderUsesEndpointAndExplicitValues() {
        HttpEmbeddingClient client = HttpEmbeddingClient.builder()
                .endpoint(endpoint().toString())
                .model("m")
                .apiKey("k")
                .connectTimeoutMillis(200)
                .readTimeoutMillis(300)
                .build();

        assertArrayEquals(new float[] {0.25f, 0.5f, -0.75f}, client.embed("x"), 0.0001f);
    }

    @Test
    public void builderNormalisesBaseUrl() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        HttpEmbeddingClient fromPlain = HttpEmbeddingClient.builder()
                .baseUrl(base).model("m").apiKey("k").build();
        assertArrayEquals(new float[] {0.25f, 0.5f, -0.75f}, fromPlain.embed("x"), 0.0001f);

        HttpEmbeddingClient fromSlash = HttpEmbeddingClient.builder()
                .baseUrl(base + "/").model("m").apiKey("k").build();
        assertArrayEquals(new float[] {0.25f, 0.5f, -0.75f}, fromSlash.embed("x"), 0.0001f);

        HttpEmbeddingClient fromEmbeddings = HttpEmbeddingClient.builder()
                .baseUrl(base + "/embeddings").model("m").apiKey("k").build();
        assertArrayEquals(new float[] {0.25f, 0.5f, -0.75f}, fromEmbeddings.embed("x"), 0.0001f);
    }

    @Test
    public void builderRequiresEndpointAndModelAndKey() {
        assertIllegalArgument(() -> HttpEmbeddingClient.builder().model("m").apiKey("k").build());
        assertIllegalArgument(() -> HttpEmbeddingClient.builder()
                .endpoint(endpoint().toString()).apiKey("k").build());
        assertIllegalArgument(() -> HttpEmbeddingClient.builder()
                .endpoint(endpoint().toString()).model("m").build());
    }

    private void assertEmbedFails() {
        HttpEmbeddingClient client = new HttpEmbeddingClient(endpoint(), "m", "k");
        try {
            client.embed("x");
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
            assertTrue(expected.getMessage().startsWith("embedding "));
        }
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings");
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int read;
        while ((read = input.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
