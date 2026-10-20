package com.clara.core.transport;

import com.clara.core.transport.sse.SseEvent;
import com.clara.core.transport.sse.SseParserConfig;
import com.clara.utils.HttpUrisUtils;
import org.junit.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 传输契约：请求头校验、请求/响应值对象、SSE 上限、端点归一化与媒体类型判断。
 */
public class TransportContractTest {

    /**
     * 请求头校验：合法头保留顺序并冻结；非法头名/值、重复名与受控头被拒绝。
     */
    @Test
    public void validatesHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Trace", "abc");
        headers.put("Accept", "application/json");
        Map<String, String> safe = HttpHeaders.validate(headers);
        assertEquals(Arrays.asList("X-Trace", "Accept"), Arrays.asList(safe.keySet().toArray(new String[0])));
        assertThrows(UnsupportedOperationException.class, () -> safe.put("x", "y"));

        assertTrue(HttpHeaders.validate(null).isEmpty());
        assertTrue(HttpHeaders.validate(Collections.emptyMap()).isEmpty());

        assertThrows(IllegalArgumentException.class,
                () -> HttpHeaders.validate(Collections.singletonMap("bad name", "v")));
        assertThrows(IllegalArgumentException.class,
                () -> HttpHeaders.validate(Collections.singletonMap("X", null)));
        assertThrows(IllegalArgumentException.class,
                () -> HttpHeaders.validate(Collections.singletonMap("X", "a\rb")));
        assertThrows(IllegalArgumentException.class,
                () -> HttpHeaders.validate(Collections.singletonMap("X", "a\nb")));
        assertThrows(IllegalArgumentException.class,
                () -> HttpHeaders.validate(Collections.singletonMap("Host", "example.com")));
        Map<String, String> duplicate = new LinkedHashMap<>();
        duplicate.put("X", "1");
        duplicate.put("x", "2");
        assertThrows(IllegalArgumentException.class, () -> HttpHeaders.validate(duplicate));

        Map<String, List<String>> response = new HashMap<>();
        response.put("Content-Type", Arrays.asList("application/json"));
        assertEquals("application/json", HttpHeaders.firstValue(response, "content-type"));
        assertNull(HttpHeaders.firstValue(null, "x"));
        assertNull(HttpHeaders.firstValue(response, null));
        assertNull(HttpHeaders.firstValue(response, "missing"));
        response.put("Empty", Collections.emptyList());
        assertNull(HttpHeaders.firstValue(response, "Empty"));
    }

    /**
     * 请求对象：方法归一化、GET/DELETE 禁止请求体、请求体与头部做防御性复制。
     */
    @Test
    public void validatesRawRequest() {
        byte[] body = {1, 2};
        RawHttpRequest request = new RawHttpRequest("post", URI.create("https://example.com/x"),
                Collections.singletonMap("X", "1"), body);
        assertEquals("POST", request.getMethod());
        assertEquals(URI.create("https://example.com/x"), request.getEndpoint());
        assertEquals("1", request.getHeaders().get("X"));
        body[0] = 9;
        assertArrayEquals(new byte[]{1, 2}, request.getBody());
        byte[] copy = request.getBody();
        copy[1] = 9;
        assertArrayEquals(new byte[]{1, 2}, request.getBody());

        assertEquals("GET", new RawHttpRequest("get", URI.create("https://example.com"), null, null).getMethod());
        assertArrayEquals(new byte[0], new RawHttpRequest("GET", URI.create("https://example.com"), null, null).getBody());

        assertThrows(IllegalArgumentException.class,
                () -> new RawHttpRequest(null, URI.create("https://example.com"), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new RawHttpRequest("PUT", URI.create("https://example.com"), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new RawHttpRequest("GET", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new RawHttpRequest("GET", URI.create("https://example.com"), null, new byte[]{1}));
        assertThrows(IllegalArgumentException.class,
                () -> new RawHttpRequest("DELETE", URI.create("https://example.com"), null, new byte[]{1}));
    }

    /**
     * 响应对象：状态码范围校验、2xx 判断、头部过滤与响应体复制。
     */
    @Test
    public void validatesRawResponse() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", Arrays.asList("application/json"));
        headers.put("Bad", Arrays.asList((String) null));
        headers.put(null, Arrays.asList("x"));
        byte[] body = {1};
        RawHttpResponse response = new RawHttpResponse(200, headers, body);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.isSuccessful());
        assertEquals("application/json", response.getHeaders().get("Content-Type").get(0));
        assertFalse(response.getHeaders().containsKey("Bad"));
        body[0] = 9;
        assertArrayEquals(new byte[]{1}, response.getBody());

        assertFalse(new RawHttpResponse(302, Collections.emptyMap(), new byte[0]).isSuccessful());
        assertThrows(IllegalArgumentException.class,
                () -> new RawHttpResponse(99, Collections.emptyMap(), new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new RawHttpResponse(600, Collections.emptyMap(), new byte[0]));
        assertTrue(new RawHttpResponse(200, null, new byte[0]).getHeaders().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new RawHttpResponse(200, Collections.emptyMap(), null));
    }

    /**
     * SSE 解析上限与事件值对象。
     */
    @Test
    public void configuresSse() {
        SseParserConfig config = SseParserConfig.defaults();
        assertEquals(SseParserConfig.DEFAULT_MAX_LINE_BYTES, config.getMaxLineBytes());
        assertEquals(SseParserConfig.DEFAULT_MAX_EVENT_BYTES, config.getMaxEventBytes());
        assertEquals(SseParserConfig.DEFAULT_MAX_EVENTS, config.getMaxEvents());
        assertEquals(SseParserConfig.DEFAULT_MAX_TOTAL_BYTES, config.getMaxTotalBytes());
        assertFalse(config.isDispatchFinalEventOnEof());

        SseParserConfig custom = new SseParserConfig(10, 20, 3, 40L, true);
        assertEquals(10, custom.getMaxLineBytes());
        assertEquals(20, custom.getMaxEventBytes());
        assertEquals(3, custom.getMaxEvents());
        assertEquals(40L, custom.getMaxTotalBytes());
        assertTrue(custom.isDispatchFinalEventOnEof());

        assertThrows(IllegalArgumentException.class, () -> new SseParserConfig(0, 1, 1, 1L, false));
        assertThrows(IllegalArgumentException.class, () -> new SseParserConfig(1, 0, 1, 1L, false));
        assertThrows(IllegalArgumentException.class, () -> new SseParserConfig(1, 1, 0, 1L, false));
        assertThrows(IllegalArgumentException.class, () -> new SseParserConfig(1, 1, 1, 0L, false));
        assertThrows(IllegalArgumentException.class, () -> new SseParserConfig(1, 5, 1, 4L, false));

        SseEvent event = new SseEvent("message", "data", "1", 1000L);
        assertEquals("message", event.getEvent());
        assertEquals("data", event.getData());
        assertEquals("1", event.getId());
        assertEquals(Long.valueOf(1000L), event.getRetryMillis());
    }

    /**
     * 传输策略：依赖 SSE 上限与响应上限的关系，默认头被校验。
     */
    @Test
    public void buildsTransportPolicy() {
        IEndpointPolicy allowAll = endpoint -> endpoint != null;
        SseParserConfig sse = new SseParserConfig(10, 20, 30, 40L, false);
        HttpTransportPolicy policy = new HttpTransportPolicy(allowAll, 1000, 2000, 4096, 8192,
                Collections.singletonMap("X", "1"), sse);
        assertEquals(allowAll, policy.getEndpointPolicy());
        assertEquals(1000, policy.getConnectTimeoutMillis());
        assertEquals(2000, policy.getReadTimeoutMillis());
        assertEquals(4096, policy.getMaxRequestBytes());
        assertEquals(8192, policy.getMaxResponseBytes());
        assertEquals("1", policy.getDefaultHeaders().get("X"));
        assertEquals(sse, policy.getSseParserConfig());
        assertThrows(UnsupportedOperationException.class, () -> policy.getDefaultHeaders().put("y", "2"));

        assertThrows(IllegalArgumentException.class,
                () -> new HttpTransportPolicy(null, 1, 1, 1, 1, null, sse));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpTransportPolicy(allowAll, 0, 1, 1, 1, null, sse));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpTransportPolicy(allowAll, 1, 0, 1, 1, null, sse));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpTransportPolicy(allowAll, 1, 1, 0, 1, null, sse));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpTransportPolicy(allowAll, 1, 1, 1, 0, null, sse));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpTransportPolicy(allowAll, 1, 1, 1, 1, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpTransportPolicy(allowAll, 1, 1, 1, 39, null, sse));
    }

    /**
     * 端点校验与源站归一化。
     */
    @Test
    public void normalizesEndpoints() {
        assertEquals(URI.create("https://example.com/a"), HttpUrisUtils.requireAbsolute(
                URI.create("HTTPS://example.com/a"), false));
        assertEquals("https://example.com:443", HttpUrisUtils.origin(
                URI.create("https://example.com/a"), false));
        assertEquals("http://example.com:80", HttpUrisUtils.origin(
                URI.create("http://example.com"), true));
        assertEquals("https://[::1]:8443", HttpUrisUtils.origin(
                URI.create("https://[::1]:8443/x"), false));
        assertEquals("https://example.com:443", HttpUrisUtils.normalizeOrigin("https://example.com/", false));

        assertThrows(IllegalArgumentException.class, () -> HttpUrisUtils.requireAbsolute(null, true));
        assertThrows(IllegalArgumentException.class, () -> HttpUrisUtils.requireAbsolute(URI.create("/x"), true));
        assertThrows(IllegalArgumentException.class, () -> HttpUrisUtils.requireAbsolute(URI.create("ftp://example.com"), true));
        assertThrows(IllegalArgumentException.class, () -> HttpUrisUtils.requireAbsolute(URI.create("http://example.com"), false));
        assertThrows(IllegalArgumentException.class,
                () -> HttpUrisUtils.requireAbsolute(URI.create("https://user@example.com"), false));
        assertThrows(IllegalArgumentException.class,
                () -> HttpUrisUtils.requireAbsolute(URI.create("https://example.com#f"), false));
        assertThrows(IllegalArgumentException.class, () -> HttpUrisUtils.normalizeOrigin(" ", true));
        assertThrows(IllegalArgumentException.class, () -> HttpUrisUtils.normalizeOrigin("https://example.com/a", true));
        assertThrows(IllegalArgumentException.class, () -> HttpUrisUtils.normalizeOrigin("https://example.com?a", true));
        assertThrows(IllegalArgumentException.class, () -> HttpUrisUtils.normalizeOrigin("https://example.com#a", true));
        assertThrows(IllegalArgumentException.class, () -> HttpUrisUtils.normalizeOrigin("http://exa mple.com", true));
    }

    /**
     * 媒体类型判断忽略大小写与参数。
     */
    @Test
    public void detectsEventStream() {
        assertTrue(HttpMediaTypes.isEventStream("text/event-stream"));
        assertTrue(HttpMediaTypes.isEventStream("TEXT/EVENT-STREAM; charset=utf-8"));
        assertFalse(HttpMediaTypes.isEventStream(null));
        assertFalse(HttpMediaTypes.isEventStream("application/json"));
    }
}