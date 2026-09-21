package com.clara.mcp.transport.http;

import com.clara.kernel.exception.ErrorCategory;
import com.clara.kernel.exception.ErrorCodes;
import com.clara.mcp.client.JsonRpcNotification;
import com.clara.mcp.client.JsonRpcRequest;
import com.clara.mcp.client.JsonRpcResponse;
import com.clara.mcp.transport.McpDecodedMessage;
import com.clara.mcp.transport.McpJsonCodec;
import com.clara.mcp.transport.McpTransport;
import com.clara.mcp.transport.McpTransportErrors;
import com.clara.mcp.transport.McpTransportListener;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实现 MCP Streamable HTTP 传输，支持 JSON 或 SSE 响应、会话 ID、服务端事件流和请求取消。
 *
 * <p>常规用法：通过 {@link #builder} 配置 endpoint、允许的主机、认证请求头和响应大小上限，构建后调用
 * {@link #start}；协议协商完成后设置版本，按需调用 {@link #startEventStream}，结束时调用 {@link #close()}。
 *
 * <p>注意：默认只允许 HTTPS，启用明文 HTTP 仅适合可信本地环境；endpoint 主机必须在允许列表中。
 * 请求 ID 不得并发重复，响应体受大小限制；会话 ID 和 Last-Event-ID 由传输维护，不应由业务请求头覆盖。
 */
public final class StreamableHttpMcpTransport implements McpTransport {
    private final URI endpoint;
    private final McpHeadersProvider headersProvider;
    private final McpJsonCodec codec;
    private final int connectTimeoutMillis;
    private final int maxResponseBytes;
    private final ExecutorService executor;
    private final ConcurrentMap<String, HttpCall> active = new ConcurrentHashMap<String, HttpCall>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean eventStreamStarted = new AtomicBoolean();
    private final AtomicReference<String> sessionId = new AtomicReference<String>();
    private final AtomicReference<String> lastEventId = new AtomicReference<String>();
    private final AtomicLong retryMillis = new AtomicLong(5000L);
    private volatile String protocolVersion;
    private volatile McpTransportListener listener;
    private volatile HttpURLConnection eventStreamConnection;

    private StreamableHttpMcpTransport(Builder builder) {
        this.endpoint = validateEndpoint(builder.endpoint, builder.allowedHosts, builder.allowHttp);
        if (builder.codec == null) { throw new IllegalArgumentException("codec must not be null"); }
        if (builder.connectTimeoutMillis <= 0 || builder.maxResponseBytes <= 0) {
            throw new IllegalArgumentException("transport limits must be positive");
        }
        this.codec = builder.codec; this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.maxResponseBytes = builder.maxResponseBytes;
        this.headersProvider = builder.headersProvider == null
                ? new McpHeadersProvider() {
                    public Map<String, String> getHeaders() { return Collections.emptyMap(); }
                } : builder.headersProvider;
        this.executor = Executors.newCachedThreadPool(new DaemonThreadFactory());
    }

    public static Builder builder() { return new Builder(); }
    public void setProtocolVersion(String value) {
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException("protocolVersion must not be blank"); }
        protocolVersion = value;
    }
    public String getSessionId() { return sessionId.get(); }

    public void start(McpTransportListener listener) {
        if (listener == null) { throw new IllegalArgumentException("listener must not be null"); }
        if (closed.get()) { throw closedError(); }
        if (!started.compareAndSet(false, true)) { throw new IllegalStateException("transport is already started"); }
        this.listener = listener;
    }

    public CompletableFuture<JsonRpcResponse> sendRequest(final JsonRpcRequest request,
            final long timeoutMillis) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        if (timeoutMillis <= 0L) { throw new IllegalArgumentException("timeoutMillis must be positive"); }
        ensureStarted();
        final HttpCall call = new HttpCall();
        if (active.putIfAbsent(request.getId(), call) != null) {
            return failed(McpTransportErrors.error(ErrorCodes.MCP_DUPLICATE_REQUEST_ID,
                    ErrorCategory.CONFLICT, false));
        }
        final CompletableFuture<JsonRpcResponse> future = call.future;
        executor.execute(new Runnable() {
            public void run() {
                if (future.isCancelled()) return;
                try {
                    future.complete(executePost(codec.encode(request), request.getId(), timeoutMillis, call));
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                } finally {
                    active.remove(request.getId(), call);
                }
            }
        });
        future.whenComplete((result, failure) -> {
            if (future.isCancelled()) call.disconnect();
        });
        return future;
    }

    public void sendNotification(final JsonRpcNotification notification) {
        if (notification == null) { throw new IllegalArgumentException("notification must not be null"); }
        ensureStarted();
        CompletableFuture.runAsync(() -> {
            try { executeNotification(codec.encode(notification)); }
            catch (RuntimeException exception) { reportFailure(exception); }
        }, executor);
    }

    public boolean cancel(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) { throw new IllegalArgumentException("requestId must not be blank"); }
        HttpCall call = active.remove(requestId);
        if (call == null) return false;
        call.disconnect();
        call.future.cancel(false);
        return true;
    }

    public void startEventStream() {
        ensureStarted();
        if (!eventStreamStarted.compareAndSet(false, true)) return;
        executor.execute(new Runnable() { public void run() { eventStreamLoop(); } });
    }

    public void checkHealth() { ensureStarted(); }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        HttpURLConnection stream = eventStreamConnection;
        if (stream != null) stream.disconnect();
        for (HttpCall call : active.values()) call.disconnect();
        active.clear(); executor.shutdownNow();
    }

    private JsonRpcResponse executePost(String body, String requestId, long timeoutMillis,
            HttpCall call) {
        HttpURLConnection connection = null;
        try {
            connection = open("POST", timeout(timeoutMillis)); call.connection = connection;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode(); captureSession(connection);
            if (status < 200 || status >= 300) {
                closeResponse(connection);
                throw ioError();
            }
            String contentType = header(connection, "Content-Type");
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
                return readSseResponse(connection.getInputStream(), requestId);
            }
            McpDecodedMessage message = codec.decode(readBody(connection.getInputStream()));
            if (message.getType() != McpDecodedMessage.Type.RESPONSE
                    || !requestId.equals(message.getResponse().getId())) { throw invalidMessage(); }
            return message.getResponse();
        } catch (SocketTimeoutException exception) {
            throw McpTransportErrors.error(ErrorCodes.MCP_REQUEST_TIMEOUT, ErrorCategory.TIMEOUT, true);
        } catch (IOException exception) {
            if (closed.get()) { throw closedError(); }
            throw ioError();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void executeNotification(String body) {
        HttpURLConnection connection = null;
        try {
            connection = open("POST", connectTimeoutMillis);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode(); captureSession(connection);
            if (status < 200 || status >= 300) {
                closeResponse(connection);
                throw ioError();
            }
            InputStream input = connection.getInputStream();
            if (input != null) input.close();
        } catch (IOException exception) {
            throw ioError();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private JsonRpcResponse readSseResponse(InputStream input, String requestId) throws IOException {
        final AtomicReference<JsonRpcResponse> response = new AtomicReference<JsonRpcResponse>();
        readSse(input, new EventConsumer() {
            public boolean accept(SseEvent event) {
                if (event.getData().isEmpty()) return true;
                McpDecodedMessage message = codec.decode(event.getData());
                if (message.getType() == McpDecodedMessage.Type.RESPONSE
                        && requestId.equals(message.getResponse().getId())) {
                    response.set(message.getResponse()); return false;
                }
                dispatch(message); return true;
            }
        });
        if (response.get() == null) { throw new IOException("SSE response ended without matching response"); }
        return response.get();
    }

    private void eventStreamLoop() {
        while (!closed.get()) {
            HttpURLConnection connection = null;
            try {
                connection = open("GET", 0); eventStreamConnection = connection;
                String eventId = lastEventId.get();
                if (eventId != null) connection.setRequestProperty("Last-Event-ID", eventId);
                int status = connection.getResponseCode(); captureSession(connection);
                if (status < 200 || status >= 300) {
                    closeResponse(connection);
                    throw new IOException("event stream rejected");
                }
                readSse(connection.getInputStream(), new EventConsumer() {
                    public boolean accept(SseEvent event) {
                        if (event.getId() != null) lastEventId.set(event.getId());
                        if (event.getRetryMillis() != null) retryMillis.set(event.getRetryMillis());
                        if (!event.getData().isEmpty()) dispatch(codec.decode(event.getData()));
                        return !closed.get();
                    }
                });
            } catch (RuntimeException exception) {
                if (!closed.get()) reportFailure(exception);
            } catch (IOException exception) {
                if (!closed.get()) reportFailure(ioError());
            } finally {
                if (connection != null) connection.disconnect();
                eventStreamConnection = null;
            }
            if (!closed.get()) {
                try { Thread.sleep(Math.min(retryMillis.get(), 60000L)); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void readSse(InputStream input, EventConsumer consumer) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        SseEventParser parser = new SseEventParser(maxResponseBytes);
        String line;
        while ((line = readBoundedLine(reader, maxResponseBytes)) != null) {
            SseEvent event;
            try { event = parser.accept(line); }
            catch (SseEventTooLargeException exception) { throw messageTooLarge(); }
            if (event != null && !consumer.accept(event)) return;
        }
        SseEvent finalEvent;
        try { finalEvent = parser.accept(null); }
        catch (SseEventTooLargeException exception) { throw messageTooLarge(); }
        if (finalEvent != null) consumer.accept(finalEvent);
    }

    private void dispatch(McpDecodedMessage message) {
        try {
            if (message.getType() == McpDecodedMessage.Type.NOTIFICATION) {
                listener.onNotification(message.getNotification());
            } else if (message.getType() == McpDecodedMessage.Type.SERVER_REQUEST) {
                listener.onServerRequest(message.getRequest());
            }
        } catch (RuntimeException failure) {
            reportFailure(failure);
        }
    }

    private void reportFailure(Throwable failure) {
        try { listener.onFailure(failure); }
        catch (RuntimeException ignored) { }
    }

    private HttpURLConnection open(String method, int readTimeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint.toString()).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeout);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        if ("POST".equals(method)) {
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
        }
        String version = protocolVersion;
        if (version != null) connection.setRequestProperty("MCP-Protocol-Version", version);
        String currentSession = sessionId.get();
        if (currentSession != null) connection.setRequestProperty("Mcp-Session-Id", currentSession);
        Map<String, String> headers = headersProvider.getHeaders();
        if (headers != null) for (Map.Entry<String, String> header : headers.entrySet()) {
            validateHeader(header.getKey(), header.getValue());
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        return connection;
    }

    private String readBody(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() + count > maxResponseBytes) { throw messageTooLarge(); }
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private void captureSession(HttpURLConnection connection) {
        String value = header(connection, "Mcp-Session-Id");
        if (value != null && !value.trim().isEmpty()) sessionId.set(value);
    }
    private static String header(HttpURLConnection connection, String name) {
        return connection.getHeaderField(name);
    }
    private static void closeResponse(HttpURLConnection connection) {
        InputStream input = connection.getErrorStream();
        if (input == null) {
            try { input = connection.getInputStream(); }
            catch (IOException ignored) { }
        }
        if (input != null) try { input.close(); } catch (IOException ignored) { }
    }
    private static int timeout(long value) { return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value; }
    private void ensureStarted() {
        if (closed.get()) { throw closedError(); }
        if (!started.get()) { throw new IllegalStateException("transport is not started"); }
    }
    private static void validateHeader(String name, String value) {
        if (name == null || value == null || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid HTTP header");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if ("host".equals(lower) || "content-length".equals(lower) || "connection".equals(lower)) {
            throw new IllegalArgumentException("restricted HTTP header");
        }
    }
    private static URI validateEndpoint(URI endpoint, Set<String> allowedHosts, boolean allowHttp) {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) { throw new IllegalArgumentException("endpoint is invalid"); }
        String scheme = endpoint.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !(allowHttp && "http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("endpoint scheme is not allowed");
        }
        if (allowedHosts == null || !containsIgnoreCase(allowedHosts, endpoint.getHost())) {
            throw new IllegalArgumentException("endpoint host is not allowlisted");
        }
        return endpoint.normalize();
    }
    private static boolean containsIgnoreCase(Set<String> values, String expected) {
        for (String value : values) if (value != null && value.equalsIgnoreCase(expected)) return true;
        return false;
    }
    private static String readBoundedLine(BufferedReader reader, int maximum) throws IOException {
        StringBuilder value = new StringBuilder(); int current;
        while ((current = reader.read()) >= 0) {
            if (current == '\n') break;
            if (current != '\r') value.append((char) current);
            if (value.length() > maximum) { throw messageTooLarge(); }
        }
        return current < 0 && value.length() == 0 ? null : value.toString();
    }
    private static <T> CompletableFuture<T> failed(RuntimeException exception) {
        CompletableFuture<T> future = new CompletableFuture<T>(); future.completeExceptionally(exception); return future;
    }
    private static RuntimeException closedError() {
        return McpTransportErrors.error(ErrorCodes.MCP_TRANSPORT_CLOSED, ErrorCategory.CANCELLED, false);
    }
    private static RuntimeException ioError() {
        return McpTransportErrors.error(ErrorCodes.MCP_TRANSPORT_IO_ERROR, ErrorCategory.EXTERNAL_SERVICE, true);
    }
    private static RuntimeException invalidMessage() {
        return McpTransportErrors.error(ErrorCodes.MCP_INVALID_MESSAGE, ErrorCategory.VALIDATION, false);
    }
    private static RuntimeException messageTooLarge() {
        return McpTransportErrors.error(ErrorCodes.MCP_MESSAGE_TOO_LARGE, ErrorCategory.RESOURCE_LIMIT, false);
    }
    /**
     * 消费事件数据。
     */
    private interface EventConsumer { boolean accept(SseEvent event); }
    /**
     * 表示一次HTTP调用。
     */
    private static final class HttpCall {
        private volatile HttpURLConnection connection;
        private final CompletableFuture<JsonRpcResponse> future =
                new CompletableFuture<JsonRpcResponse>();
        private void disconnect() { HttpURLConnection value = connection; if (value != null) value.disconnect(); }
    }
    /**
     * 负责创建守护进程线程实例。
     */
    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "clara-mcp-http-" + sequence.incrementAndGet());
            thread.setDaemon(true); return thread;
        }
    }
    /**
     * 表示。
     */
    public static final class Builder {
        private URI endpoint;
        private Set<String> allowedHosts;
        private boolean allowHttp;
        private McpHeadersProvider headersProvider;
        private McpJsonCodec codec;
        private int connectTimeoutMillis = 10000;
        private int maxResponseBytes = 4 * 1024 * 1024;
        public Builder endpoint(URI value) { endpoint = value; return this; }
        public Builder allowedHosts(Set<String> value) { allowedHosts = value; return this; }
        public Builder allowHttp(boolean value) { allowHttp = value; return this; }
        public Builder headersProvider(McpHeadersProvider value) { headersProvider = value; return this; }
        public Builder codec(McpJsonCodec value) { codec = value; return this; }
        public Builder connectTimeoutMillis(int value) { connectTimeoutMillis = value; return this; }
        public Builder maxResponseBytes(int value) { maxResponseBytes = value; return this; }
        public StreamableHttpMcpTransport build() { return new StreamableHttpMcpTransport(this); }
    }
}
