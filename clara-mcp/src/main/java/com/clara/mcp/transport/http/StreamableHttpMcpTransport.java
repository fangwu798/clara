package com.clara.mcp.transport.http;

import com.clara.core.transport.HttpMediaTypes;
import com.clara.core.transport.HttpHeaders;
import com.clara.exception.HttpTransportException;
import com.clara.core.transport.HttpTransportPolicy;
import com.clara.utils.HttpUrisUtils;
import com.clara.core.transport.IEndpointPolicy;
import com.clara.core.transport.IHttpCall;
import com.clara.core.transport.IHttpTransport;
import com.clara.core.transport.RawHttpRequest;
import com.clara.core.transport.RawHttpResponse;
import com.clara.core.transport.sse.ISseEventListener;
import com.clara.core.transport.sse.SseEvent;
import com.clara.core.transport.sse.SseParserConfig;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.McpErrors;
import com.clara.mcp.api.IMcpHeadersProvider;
import com.clara.mcp.api.IMcpJsonCodec;
import com.clara.mcp.api.IMcpTransport;
import com.clara.mcp.api.IMcpTransportListener;
import com.clara.mcp.transport.McpDecodedMessage;
import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;
import com.clara.mcp.transport.jsonrpc.JsonRpcResponse;
import com.clara.transport.concurrent.DaemonThreads;
import com.clara.transport.http.DeadlineGuard;
import com.clara.transport.http.HostEndpointPolicy;
import com.clara.transport.http.JdkHttpTransport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实现 MCP Streamable HTTP 传输，支持 JSON 或 SSE 响应、会话 ID、服务端事件流和请求取消。
 *
 * <p>本类只保留 MCP 协议语义：JSON-RPC 编解码、请求 ID 关联、会话 ID、协议版本、Last-Event-ID 与重连。
 * 连接、超时、体积上限与 SSE 解析统一委托给 {@link IHttpTransport}。
 */
public final class StreamableHttpMcpTransport implements IMcpTransport {
    private final URI endpoint;
    private final IMcpHeadersProvider headersProvider;
    private final IMcpJsonCodec codec;
    private final HttpTransportPolicy policy;
    private final HttpTransportPolicy eventStreamPolicy;
    private final IHttpTransport httpTransport;
    private final ExecutorService executor;
    private final ConcurrentMap<String, ActiveCall> active = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean eventStreamStarted = new AtomicBoolean();
    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private final AtomicReference<String> lastEventId = new AtomicReference<>();
    private final AtomicLong retryMillis = new AtomicLong(5000L);
    private volatile String protocolVersion;
    private final AtomicReference<IMcpTransportListener> listener = new AtomicReference<>();
    private final AtomicReference<IHttpCall> eventStreamCall = new AtomicReference<>();

    private StreamableHttpMcpTransport(Builder builder) {
        IEndpointPolicy endpointPolicy = new HostEndpointPolicy(builder.allowedHosts, builder.allowHttp);
        this.endpoint = validateEndpoint(builder.endpoint, endpointPolicy, builder.allowHttp);
        if (builder.codec == null) {
            throw new IllegalArgumentException("codec must not be null");
        }
        if (builder.connectTimeoutMillis <= 0 || builder.maxResponseBytes <= 0) {
            throw new IllegalArgumentException("transport limits must be positive");
        }
        this.codec = builder.codec;
        this.headersProvider = builder.headersProvider == null
                ? Collections::emptyMap
                : builder.headersProvider;
        int maxResponseBytes = builder.maxResponseBytes;
        SseParserConfig sse = new SseParserConfig(
                Math.min(SseParserConfig.DEFAULT_MAX_LINE_BYTES, maxResponseBytes),
                Math.min(SseParserConfig.DEFAULT_MAX_EVENT_BYTES, maxResponseBytes),
                SseParserConfig.DEFAULT_MAX_EVENTS, maxResponseBytes, true);
        this.policy = new HttpTransportPolicy(endpointPolicy, builder.connectTimeoutMillis,
                Integer.MAX_VALUE, maxResponseBytes, maxResponseBytes,
                Collections.<String, String>emptyMap(), sse);
        this.eventStreamPolicy = new HttpTransportPolicy(endpointPolicy,
                builder.connectTimeoutMillis, Integer.MAX_VALUE, maxResponseBytes,
                Integer.MAX_VALUE, Collections.<String, String>emptyMap(),
                new SseParserConfig(
                        Math.min(SseParserConfig.DEFAULT_MAX_LINE_BYTES, maxResponseBytes),
                        Math.min(SseParserConfig.DEFAULT_MAX_EVENT_BYTES, maxResponseBytes),
                        Integer.MAX_VALUE, Integer.MAX_VALUE, true));
        this.httpTransport = new JdkHttpTransport();
        this.executor = Executors.newCachedThreadPool(
                DaemonThreads.daemonThreadFactory("clara-mcp-http-"));
    }

    public static Builder builder() {
        return new Builder();
    }

    public void setProtocolVersion(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("protocolVersion must not be blank");
        }
        protocolVersion = value;
    }

    public String getSessionId() {
        return sessionId.get();
    }

    public void start(IMcpTransportListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (closed.get()) {
            throw closedError();
        }
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("transport is already started");
        }
        this.listener.set(listener);
    }

    public CompletableFuture<JsonRpcResponse> sendRequest(final JsonRpcRequest request,
            final long timeoutMillis) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        ensureStarted();
        final ActiveCall call = new ActiveCall();
        if (active.putIfAbsent(request.getId(), call) != null) {
            return failed(McpErrors.error(ErrorCodes.MCP_DUPLICATE_REQUEST_ID,
                    ErrorCategoryEnum.CONFLICT, false));
        }
        final DeadlineGuard guard = DeadlineGuard.after(timeoutMillis);
        call.guard.set(guard);
        executor.execute(() -> executeRequest(request, call, guard));
        call.future.whenComplete((result, failure) -> {
            if (call.future.isCancelled()) {
                call.cancel();
            }
        });
        return call.future;
    }

    private void executeRequest(JsonRpcRequest request, ActiveCall call, DeadlineGuard guard) {
        IHttpCall httpCall = null;
        try {
            if (call.future.isCancelled()) {
                return;
            }
            httpCall = httpTransport.newCall(postRequest(codec.encode(request)), policy, guard);
            call.httpCall.set(httpCall);
            if (call.future.isCancelled()) {
                return;
            }
            handleResponse(request, call, httpCall.execute(new RequestSseListener(request.getId(), call.future)));
        } catch (HttpTransportException exception) {
            call.future.completeExceptionally(map(exception));
        } catch (Throwable failure) { // NOSONAR 必须兜住 Error，否则 future 永远不会完成
            call.future.completeExceptionally(failure);
        } finally {
            active.remove(request.getId(), call);
            if (httpCall != null) {
                httpCall.close();
            }
        }
    }

    private void handleResponse(JsonRpcRequest request, ActiveCall call, RawHttpResponse response) {
        if (!response.isSuccessful()) {
            throw ioError();
        }
        if (response.getBody().length > 0) {
            completeFromBody(request, call, response);
        } else if (!call.future.isDone()) {
            throw isEventStream(response) ? ioError() : invalidMessage();
        }
    }

    private void completeFromBody(JsonRpcRequest request, ActiveCall call, RawHttpResponse response) {
        McpDecodedMessage message = codec.decode(new String(response.getBody(), StandardCharsets.UTF_8));
        if (message.getType() != McpDecodedMessage.Type.RESPONSE
                || !request.getId().equals(message.getResponse().getId())) {
            throw invalidMessage();
        }
        call.future.complete(message.getResponse());
    }

    public void sendNotification(final JsonRpcNotification notification) {
        if (notification == null) {
            throw new IllegalArgumentException("notification must not be null");
        }
        ensureStarted();
        executor.execute(() -> {
            IHttpCall call = httpTransport.newCall(postRequest(codec.encode(notification)),
                    policy, DeadlineGuard.after(policy.getConnectTimeoutMillis()));
            try {
                RawHttpResponse response = call.execute();
                captureSession(response);
                if (!response.isSuccessful()) {
                    throw ioError();
                }
            } catch (HttpTransportException exception) {
                reportFailure(map(exception));
            } catch (RuntimeException exception) {
                reportFailure(exception);
            } finally {
                call.close();
            }
        });
    }

    public boolean cancel(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        ActiveCall call = active.remove(requestId);
        if (call == null) {
            return false;
        }
        call.cancel();
        return true;
    }

    public void startEventStream() {
        ensureStarted();
        if (!eventStreamStarted.compareAndSet(false, true)) {
            return;
        }
        executor.execute(this::eventStreamLoop);
    }

    public void checkHealth() {
        ensureStarted();
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IHttpCall stream = eventStreamCall.get();
        if (stream != null) {
            stream.cancel();
        }
        for (ActiveCall call : active.values()) {
            call.cancel();
        }
        active.clear();
        executor.shutdownNow();
    }

    private void eventStreamLoop() {
        while (!closed.get()) {
            openEventStream();
            if (!sleepBeforeReconnect()) {
                return;
            }
        }
    }

    private void openEventStream() {
        IHttpCall call = null;
        try {
            call = httpTransport.newCall(eventStreamRequest(), eventStreamPolicy,
                    DeadlineGuard.withoutDeadline());
            eventStreamCall.set(call);
            RawHttpResponse response = call.execute(new ISseEventListener() {
                @Override
                public void onStart(int status, Map<String, List<String>> headers) {
                    captureSession(headers);
                }

                @Override
                public boolean onEvent(SseEvent event) {
                    return handleEvent(event);
                }
            });
            if (!response.isSuccessful() || response.getBody().length > 0) {
                throw ioError();
            }
        } catch (HttpTransportException exception) {
            reportIfOpen(map(exception));
        } catch (RuntimeException exception) {
            reportIfOpen(exception);
        } finally {
            eventStreamCall.set(null);
            if (call != null) {
                call.close();
            }
        }
    }

    private boolean handleEvent(SseEvent event) {
        if (event.getId() != null) {
            lastEventId.set(event.getId());
        }
        if (event.getRetryMillis() != null) {
            retryMillis.set(event.getRetryMillis().longValue());
        }
        if (event.getData() != null && !event.getData().isEmpty()) {
            dispatch(codec.decode(event.getData()));
        }
        return !closed.get();
    }

    private void reportIfOpen(RuntimeException failure) {
        if (!closed.get()) {
            reportFailure(failure);
        }
    }

    private boolean sleepBeforeReconnect() {
        if (closed.get()) {
            return true;
        }
        try {
            Thread.sleep(Math.min(retryMillis.get(), 60000L));
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private RawHttpRequest postRequest(String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("Content-Type", "application/json");
        addProtocolAndSession(headers);
        addProviderHeaders(headers);
        return new RawHttpRequest("POST", endpoint, headers,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private RawHttpRequest eventStreamRequest() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "text/event-stream");
        addProtocolAndSession(headers);
        String eventId = lastEventId.get();
        if (eventId != null) {
            mergeHeader(headers, "Last-Event-ID", eventId);
        }
        addProviderHeaders(headers);
        return new RawHttpRequest("GET", endpoint, headers, null);
    }

    private void addProtocolAndSession(Map<String, String> headers) {
        String version = protocolVersion;
        if (version != null) {
            mergeHeader(headers, "MCP-Protocol-Version", version);
        }
        String currentSession = sessionId.get();
        if (currentSession != null) {
            mergeHeader(headers, "Mcp-Session-Id", currentSession);
        }
    }

    private void addProviderHeaders(Map<String, String> headers) {
        Map<String, String> provided = headersProvider.getHeaders();
        if (provided == null) {
            return;
        }
        for (Map.Entry<String, String> header : provided.entrySet()) {
            mergeHeader(headers, header.getKey(), header.getValue());
        }
    }

    private static void mergeHeader(Map<String, String> headers, String name, String value) {
        String existing = null;
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                existing = key;
                break;
            }
        }
        if (existing != null) {
            headers.remove(existing);
        }
        headers.put(name, value);
    }

    private void captureSession(RawHttpResponse response) {
        captureSession(response.getHeaders());
    }

    private void captureSession(Map<String, List<String>> headers) {
        String value = HttpHeaders.firstValue(headers, "Mcp-Session-Id");
        if (value != null && !value.trim().isEmpty()) {
            sessionId.set(value);
        }
    }

    private void dispatch(McpDecodedMessage message) {
        IMcpTransportListener current = listener.get();
        if (current == null) {
            return;
        }
        try {
            if (message.getType() == McpDecodedMessage.Type.NOTIFICATION) {
                current.onNotification(message.getNotification());
            } else if (message.getType() == McpDecodedMessage.Type.SERVER_REQUEST) {
                current.onServerRequest(message.getRequest());
            }
        } catch (RuntimeException failure) {
            reportFailure(failure);
        }
    }

    private void reportFailure(Throwable failure) {
        IMcpTransportListener current = listener.get();
        if (current == null) {
            return;
        }
        try {
            current.onFailure(failure);
        } catch (RuntimeException ignored) {
            // listener 自身抛出的异常不能反过来影响传输层的事件循环。
        }
    }

    private void ensureStarted() {
        if (closed.get()) {
            throw closedError();
        }
        if (!started.get()) {
            throw new IllegalStateException("transport is not started");
        }
    }

    private static boolean isEventStream(RawHttpResponse response) {
        return HttpMediaTypes.isEventStream(
                HttpHeaders.firstValue(response.getHeaders(), "Content-Type"));
    }

    private static URI validateEndpoint(URI endpoint, IEndpointPolicy policy, boolean allowHttp) {
        URI safe = HttpUrisUtils.requireAbsolute(endpoint, allowHttp);
        if (!policy.allows(safe)) {
            throw new IllegalArgumentException("endpoint host is not allowlisted");
        }
        return safe;
    }

    private static RuntimeException map(HttpTransportException exception) {
        switch (exception.getKind()) {
            case TIMEOUT:
                return McpErrors.error(ErrorCodes.MCP_REQUEST_TIMEOUT,
                        ErrorCategoryEnum.TIMEOUT, true);
            case RESPONSE_TOO_LARGE:
            case REQUEST_TOO_LARGE:
                return messageTooLarge();
            case PROTOCOL_ERROR:
            case ENDPOINT_DENIED:
                return invalidMessage();
            case CANCELLED:
                return McpErrors.error(ErrorCodes.INVOCATION_CANCELLED,
                        ErrorCategoryEnum.CANCELLED, false);
            case CLOSED:
                return closedError();
            case IO_ERROR:
            default:
                return ioError();
        }
    }

    private static <T> CompletableFuture<T> failed(RuntimeException exception) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(exception);
        return future;
    }

    private static RuntimeException closedError() {
        return McpErrors.error(ErrorCodes.MCP_TRANSPORT_CLOSED, ErrorCategoryEnum.CANCELLED, false);
    }

    private static RuntimeException ioError() {
        return McpErrors.error(ErrorCodes.MCP_TRANSPORT_IO_ERROR,
                ErrorCategoryEnum.EXTERNAL_SERVICE, true);
    }

    private static RuntimeException invalidMessage() {
        return McpErrors.error(ErrorCodes.MCP_INVALID_MESSAGE, ErrorCategoryEnum.VALIDATION, false);
    }

    private static RuntimeException messageTooLarge() {
        return McpErrors.error(ErrorCodes.MCP_MESSAGE_TOO_LARGE,
                ErrorCategoryEnum.RESOURCE_LIMIT, false);
    }

    /**
     * 一次进行中的请求：Future 与可取消的 HTTP 调用。
     */
    private static final class ActiveCall {
        private final CompletableFuture<JsonRpcResponse> future =
                new CompletableFuture<>();
        private final AtomicReference<DeadlineGuard> guard = new AtomicReference<>();
        private final AtomicReference<IHttpCall> httpCall = new AtomicReference<>();

        private void cancel() {
            DeadlineGuard currentGuard = guard.get();
            if (currentGuard != null) {
                currentGuard.cancel();
            }
            IHttpCall currentCall = httpCall.get();
            if (currentCall != null) {
                currentCall.cancel();
            }
            future.cancel(false);
        }
    }

    /**
     * 把 SSE 事件解码为 JSON-RPC，并完成匹配当前请求 ID 的响应。
     */
    private final class RequestSseListener implements ISseEventListener {
        private final String requestId;
        private final CompletableFuture<JsonRpcResponse> future;

        private RequestSseListener(String requestId, CompletableFuture<JsonRpcResponse> future) {
            this.requestId = requestId;
            this.future = future;
        }

        @Override
        public void onStart(int status, Map<String, List<String>> headers) {
            captureSession(headers);
        }

        public boolean onEvent(SseEvent event) {
            if (event.getData() == null || event.getData().isEmpty()) {
                return true;
            }
            McpDecodedMessage message = codec.decode(event.getData());
            if (message.getType() == McpDecodedMessage.Type.RESPONSE
                    && requestId.equals(message.getResponse().getId())) {
                future.complete(message.getResponse());
                return false;
            }
            dispatch(message);
            return true;
        }
    }

    public static final class Builder {
        private URI endpoint;
        private Set<String> allowedHosts;
        private boolean allowHttp;
        private IMcpHeadersProvider headersProvider;
        private IMcpJsonCodec codec;
        private int connectTimeoutMillis = 10000;
        private int maxResponseBytes = 4 * 1024 * 1024;

        public Builder endpoint(URI value) {
            endpoint = value;
            return this;
        }

        public Builder allowedHosts(Set<String> value) {
            allowedHosts = value;
            return this;
        }

        public Builder allowHttp(boolean value) {
            allowHttp = value;
            return this;
        }

        public Builder headersProvider(IMcpHeadersProvider value) {
            headersProvider = value;
            return this;
        }

        public Builder codec(IMcpJsonCodec value) {
            codec = value;
            return this;
        }

        public Builder connectTimeoutMillis(int value) {
            connectTimeoutMillis = value;
            return this;
        }

        public Builder maxResponseBytes(int value) {
            maxResponseBytes = value;
            return this;
        }

        public StreamableHttpMcpTransport build() {
            return new StreamableHttpMcpTransport(this);
        }
    }
}
