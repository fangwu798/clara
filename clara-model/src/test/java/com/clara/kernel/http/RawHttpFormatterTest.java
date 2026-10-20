package com.clara.kernel.http;

import com.clara.core.transport.RawHttpRequest;
import com.clara.core.transport.RawHttpResponse;
import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Raw HTTP rendering rules for requests and responses.
 */
public class RawHttpFormatterTest {

    @Test
    public void formatsRequestWithQueryAndMasksAuthorization() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer secret-value");
        headers.put("Content-Type", "application/json");
        RawHttpRequest request = new RawHttpRequest("post", URI.create("https://api.example.com/v1/chat?stream=true"),
                headers, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        String text = RawHttpFormatter.format(request);

        assertTrue(text.startsWith("POST /v1/chat?stream=true HTTP/1.1\r\n"));
        assertTrue(text.contains("Host: api.example.com\r\n"));
        assertTrue(text.contains("Authorization: Bearer "));
        assertFalse(text.contains("secret-value"));
        assertTrue(text.contains("Content-Type: application/json\r\n"));
        assertTrue(text.endsWith("\r\n\r\n{\"a\":1}"));
    }

    @Test
    public void formatsRequestWithoutQueryHostOrBody() {
        RawHttpRequest request = new RawHttpRequest("GET", URI.create("http://localhost:8080/ping"),
                Collections.emptyMap(), null);

        String text = RawHttpFormatter.format(request);

        assertTrue(text.startsWith("GET /ping HTTP/1.1\r\n"));
        assertTrue(text.contains("Host: localhost:8080\r\n"));
        assertTrue(text.endsWith("\r\n\r\n"));
    }

    @Test
    public void formatsRelativeRequestWithoutHostHeader() {
        RawHttpRequest request = new RawHttpRequest("DELETE", URI.create("/items/1"),
                null, null);

        String text = RawHttpFormatter.format(request);

        assertEquals("DELETE /items/1 HTTP/1.1\r\n\r\n", text);
    }

    @Test
    public void formatsResponseWithRepeatedHeadersAndBody() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Set-Cookie", Arrays.asList("a=1", "b=2"));
        headers.put("X-Trace", Collections.singletonList("t-1"));
        RawHttpResponse response = new RawHttpResponse(201, headers,
                "created".getBytes(StandardCharsets.UTF_8));

        String text = RawHttpFormatter.format(response);

        assertTrue(text.startsWith("HTTP/1.1 201 Created\r\n"));
        assertTrue(text.contains("Set-Cookie: a=1\r\n"));
        assertTrue(text.contains("Set-Cookie: b=2\r\n"));
        assertTrue(text.contains("X-Trace: t-1\r\n"));
        assertTrue(text.endsWith("\r\n\r\ncreated"));
    }

    @Test
    public void rendersAllKnownReasonPhrasesAndUnknownAsEmpty() {
        assertEquals("HTTP/1.1 200 OK\r\n\r\n", text(200));
        assertEquals("HTTP/1.1 202 Accepted\r\n\r\n", text(202));
        assertEquals("HTTP/1.1 204 No Content\r\n\r\n", text(204));
        assertEquals("HTTP/1.1 206 Partial Content\r\n\r\n", text(206));
        assertEquals("HTTP/1.1 301 Moved Permanently\r\n\r\n", text(301));
        assertEquals("HTTP/1.1 302 Found\r\n\r\n", text(302));
        assertEquals("HTTP/1.1 304 Not Modified\r\n\r\n", text(304));
        assertEquals("HTTP/1.1 400 Bad Request\r\n\r\n", text(400));
        assertEquals("HTTP/1.1 401 Unauthorized\r\n\r\n", text(401));
        assertEquals("HTTP/1.1 403 Forbidden\r\n\r\n", text(403));
        assertEquals("HTTP/1.1 404 Not Found\r\n\r\n", text(404));
        assertEquals("HTTP/1.1 405 Method Not Allowed\r\n\r\n", text(405));
        assertEquals("HTTP/1.1 408 Request Timeout\r\n\r\n", text(408));
        assertEquals("HTTP/1.1 409 Conflict\r\n\r\n", text(409));
        assertEquals("HTTP/1.1 413 Payload Too Large\r\n\r\n", text(413));
        assertEquals("HTTP/1.1 415 Unsupported Media Type\r\n\r\n", text(415));
        assertEquals("HTTP/1.1 422 Unprocessable Entity\r\n\r\n", text(422));
        assertEquals("HTTP/1.1 429 Too Many Requests\r\n\r\n", text(429));
        assertEquals("HTTP/1.1 500 Internal Server Error\r\n\r\n", text(500));
        assertEquals("HTTP/1.1 502 Bad Gateway\r\n\r\n", text(502));
        assertEquals("HTTP/1.1 503 Service Unavailable\r\n\r\n", text(503));
        assertEquals("HTTP/1.1 504 Gateway Timeout\r\n\r\n", text(504));
        assertEquals("HTTP/1.1 418 \r\n\r\n", text(418));
    }

    private static String text(int statusCode) {
        return RawHttpFormatter.format(new RawHttpResponse(statusCode, null, new byte[0]));
    }
}
