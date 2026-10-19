package com.clara.transport.http;

import com.clara.core.transport.HttpMediaTypes;
import com.clara.exception.HttpTransportException;
import com.clara.core.transport.HttpTransportPolicy;
import com.clara.core.transport.IExecutionGuard;
import com.clara.core.transport.IHttpCall;
import com.clara.core.transport.RawHttpRequest;
import com.clara.core.transport.RawHttpResponse;
import com.clara.core.transport.sse.ISseEventListener;
import com.clara.core.transport.sse.ISseEventParser;
import com.clara.transport.io.BoundedStreams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 JDK 8 {@link HttpURLConnection} 的一次性 HTTP 调用。
 *
 * <p>由 {@link JdkHttpTransport#newCall} 创建；实例只能执行一次，负责端点校验、超时、请求体上限、
 * 同步响应读取与 SSE 回调。取消或关闭会断开当前连接。
 */
final class HttpCall implements IHttpCall {

    /**
     * 本次调用的请求快照。
     */
    private final RawHttpRequest request;

    /**
     * 本次调用的传输策略，决定超时、体量上限与默认请求头。
     */
    private final HttpTransportPolicy policy;

    /**
     * 取消、截止时间与剩余时间的执行守卫，由上层调用方提供。
     */
    private final IExecutionGuard guard;

    /**
     * SSE 事件解析器，按策略中的解析配置创建。
     */
    private final ISseEventParser sseParser;

    /**
     * 一次性执行标记，保证同一实例只能发起一次请求。
     */
    private final AtomicBoolean started = new AtomicBoolean();

    /**
     * 取消标记，{@link #cancel()} 后置位。
     */
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /**
     * 当前活动连接；取消线程通过它中断阻塞中的 IO。
     */
    private final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();

    /**
     * 创建一次性 HTTP 调用。
     *
     * @param request 请求快照，不能为 null
     * @param policy  传输策略，不能为 null
     * @param guard   执行守卫，不能为 null
     */
    HttpCall(RawHttpRequest request, HttpTransportPolicy policy, IExecutionGuard guard) {
        this.request = request;
        this.policy = policy;
        this.guard = guard;
        this.sseParser = new DefaultSseEventParser(policy.getSseParserConfig());
    }

    /**
     * 执行请求并读取普通响应。
     *
     * @return 响应状态、响应头与响应体
     */
    @Override
    public RawHttpResponse execute() {
        return executeInternal(null);
    }

    /**
     * 执行请求；2xx 且为事件流时逐条回调 SSE 事件。
     *
     * @param listener SSE 事件监听器，不能为 null
     * @return 响应状态与响应头，SSE 成功时响应体为空
     */
    @Override
    public RawHttpResponse execute(ISseEventListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        return executeInternal(listener);
    }

    /**
     * 标记取消并断开当前连接；重复调用无副作用。
     */
    @Override
    public void cancel() {
        cancelled.set(true);
        HttpURLConnection current = connection.getAndSet(null);
        if (current != null) {
            current.disconnect();
        }
    }

    /**
     * 判断本次调用是否已取消。
     *
     * @return 本地取消或执行守卫取消时返回 true
     */
    @Override
    public boolean isCancelled() {
        return cancelled.get() || guard.isCancelled();
    }

    /**
     * 关闭调用，语义等同于 {@link #cancel()}。
     */
    @Override
    public void close() {
        cancel();
    }

    /**
     * 校验一次性语义与请求约束，然后发起调用并读取响应。
     *
     * @param listener SSE 监听器，普通请求为 null
     * @return 响应
     */
    private RawHttpResponse executeInternal(ISseEventListener listener) {
        begin();
        byte[] body = validateRequest();

        HttpURLConnection current = null;
        try {
            current = open();
            connection.set(current);
            if (body.length > 0) {
                write(current, body);
            }
            return readResponse(current, listener);
        } catch (SocketTimeoutException exception) {
            throw HttpTransportException.timeout();
        } catch (IOException exception) {
            throw translateIoError(exception);
        } finally {
            connection.set(null);
            if (current != null) {
                current.disconnect();
            }
        }
    }

    /**
     * 占用一次性执行标记并检查执行守卫。
     */
    private void begin() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("http call is single-use");
        }
        guard.check();
    }

    /**
     * 校验端点与请求体大小。
     *
     * @return 请求体字节
     */
    private byte[] validateRequest() {
        if (!policy.getEndpointPolicy().allows(request.getEndpoint())) {
            throw HttpTransportException.endpointDenied();
        }
        byte[] body = request.getBody();
        if (body.length > policy.getMaxRequestBytes()) {
            throw HttpTransportException.requestTooLarge();
        }
        return body;
    }

    /**
     * 读取状态码、响应头与响应体；成功的事件流改为逐条回调。
     *
     * @param current  已建立连接的调用
     * @param listener SSE 监听器，可为 null
     * @return 响应
     * @throws IOException 读取状态码、响应头或响应体失败
     */
    private RawHttpResponse readResponse(HttpURLConnection current, ISseEventListener listener) throws IOException {
        guard.check();
        int status = current.getResponseCode();
        guard.check();
        Map<String, List<String>> headers = current.getHeaderFields();
        if (headers == null) {
            headers = Collections.emptyMap();
        }
        if (listener != null) {
            listener.onStart(status, headers);
            if (isEventStreamResponse(status, current.getContentType())) {
                relayEventStream(current, listener);
                return new RawHttpResponse(status, headers, new byte[0]);
            }
        }
        InputStream input = status >= 400 ? current.getErrorStream() : current.getInputStream();
        return new RawHttpResponse(status, headers,
                BoundedStreams.readAll(input, policy.getMaxResponseBytes(), guard));
    }

    /**
     * 把 IO 异常转换为传输异常；已取消时返回取消异常。
     *
     * @param exception 原始 IO 异常
     * @return 对应的传输异常
     */
    private HttpTransportException translateIoError(IOException exception) {
        if (isCancelled()) {
            return HttpTransportException.cancelled();
        }
        return HttpTransportException.ioError(exception);
    }

    /**
     * 读取事件流并逐条回调，结束后关闭输入流。
     *
     * @param current  已建立连接的调用
     * @param listener 事件监听器
     * @throws IOException 打开事件流失败
     */
    private void relayEventStream(HttpURLConnection current, ISseEventListener listener) throws IOException {
        try (InputStream input = current.getInputStream()) {
            sseParser.parse(input, guard, listener);
        }
    }

    /**
     * 创建并配置底层连接。
     *
     * @return 已配置的连接
     * @throws IOException 打开连接失败
     */
    private HttpURLConnection open() throws IOException {
        guard.check();
        if (isCancelled()) {
            throw HttpTransportException.cancelled();
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(request.getEndpoint().toString()).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setUseCaches(false);
        conn.setConnectTimeout(effectiveTimeout(policy.getConnectTimeoutMillis()));
        conn.setReadTimeout(effectiveTimeout(policy.getReadTimeoutMillis()));
        conn.setRequestMethod(request.getMethod());
        applyHeaders(conn, policy.getDefaultHeaders());
        applyHeaders(conn, request.getHeaders());
        return conn;
    }

    /**
     * 把请求头写入连接。
     *
     * @param target  目标连接
     * @param headers 请求头
     */
    private static void applyHeaders(HttpURLConnection target, Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            target.setRequestProperty(header.getKey(), header.getValue());
        }
    }

    /**
     * 计算实际超时：取配置值与守卫剩余时间的较小者。
     *
     * @param configured 配置的超时毫秒数
     * @return 实际超时毫秒数
     */
    private int effectiveTimeout(int configured) {
        long remaining = guard.remainingMillis();
        if (remaining <= 0L) {
            throw HttpTransportException.timeout();
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.min(configured, remaining));
    }

    /**
     * 写入请求体并关闭输出流。
     *
     * @param connection 目标连接
     * @param body       请求体
     * @throws IOException 写入失败
     */
    private static void write(HttpURLConnection connection, byte[] body) throws IOException {
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
            output.flush();
        }
    }

    /**
     * 判断响应是否为需要按 SSE 处理的成功事件流。
     *
     * @param status      HTTP 状态码
     * @param contentType 响应 Content-Type
     * @return 2xx 且为 SSE 媒体类型时返回 true
     */
    private static boolean isEventStreamResponse(int status, String contentType) {
        return status >= 200 && status < 300 && HttpMediaTypes.isEventStream(contentType);
    }
}
