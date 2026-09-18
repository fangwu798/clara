package com.clara.model.provider.http.impl;

import com.clara.kernel.ClaraException;
import com.clara.kernel.InvocationContext;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.ProviderSseListener;
import com.clara.model.provider.http.event.ProviderSseParser;
import com.clara.model.utils.ExceptionUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基于 JDK 8 {@link HttpURLConnection} 实现的服务提供商 HTTP 传输器，同时支持同步与 SSE 流式调用。
 *
 * <p>用途：两种调用方式共用同一套请求处理流程——端点白名单校验、请求体上限、连接/读取超时、取消检查、
 * 请求头与请求体写入、非 2xx 错误响应读取；只有拿到响应之后的处理不同：
 * <ul>
 *   <li>同步：读取完整响应体并返回，非 2xx 也正常返回错误体，由调用方按状态码处理；</li>
 *   <li>流式：要求 2xx 且内容类型为 {@code text/event-stream}，把响应体交给
 *       {@link ProviderSseParser} 边读边解析并回调 {@link ProviderSseListener}，成功时返回空响应体。</li>
 * </ul>
 *
 * <p>使用规范：
 * <ul>
 *   <li>实例只保存不可变配置，线程安全，可被多个适配器共享；每次调用使用独立的连接。</li>
 *   <li>只做同步调用时使用 {@link #Jdk8ProviderHttpTransport(ProviderHttpTransportConfig)}；
 *       需要流式调用时使用带 SSE 上限的构造函数，否则流式方法会抛
 *       {@link UnsupportedOperationException}。</li>
 *   <li>超时取“配置值”与“上下文剩余时间”的较小值，请求级截止时间不会被突破。</li>
 *   <li>失败时统一抛出 {@link ClaraException} 并携带明确的错误码与是否可重试标记（由
 *       {@link ExceptionUtils} 构造）。</li>
 * </ul>
 */
public final class Jdk8ProviderHttpTransport implements ProviderHttpTransport {
    /**
     * 平台共用的传输安全边界与资源限制配置。
     */
    private final ProviderHttpTransportConfig config;
    /**
     * SSE 事件解析器；仅在使用带 SSE 上限的构造函数时非空。
     */
    private final ProviderSseParser parser;

    /**
     * 创建仅支持同步调用的传输器。
     *
     * @param config 传输配置，提供端点白名单、超时与体积上限，不可为 null
     * @throws IllegalArgumentException 配置为 null 时
     */
    public Jdk8ProviderHttpTransport(ProviderHttpTransportConfig config) {
        this(config, null);
    }

    /**
     * 创建同时支持同步与 SSE 流式调用的传输器。
     *
     * @param config        传输配置，提供端点白名单、超时与体积上限，不可为 null
     * @param maxLineBytes  SSE 单行最大字节数
     * @param maxEventBytes SSE 单个事件最大字节数，不得超过配置的响应体上限
     * @param maxEvents     SSE 单次调用允许派发的最大事件数
     * @throws IllegalArgumentException 配置为 null，或事件上限超过响应体上限时
     */
    public Jdk8ProviderHttpTransport(ProviderHttpTransportConfig config, int maxLineBytes, int maxEventBytes, int maxEvents) {
        this(config, sseParser(config, maxLineBytes, maxEventBytes, maxEvents));
    }

    private Jdk8ProviderHttpTransport(ProviderHttpTransportConfig config, ProviderSseParser parser) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.parser = parser;
    }

    /**
     * 创建 SSE 解析器，并校验 SSE 上限与响应体上限的关系。
     *
     * @param config        传输配置，不可为 null
     * @param maxLineBytes  单行最大字节数
     * @param maxEventBytes 单个事件最大字节数
     * @param maxEvents     最大事件数
     * @return SSE 事件解析器
     */
    private static ProviderSseParser sseParser(ProviderHttpTransportConfig config,
                                               int maxLineBytes, int maxEventBytes, int maxEvents) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (maxEventBytes > config.getMaxResponseBytes()) {
            throw new IllegalArgumentException("event limit exceeds response limit");
        }
        return new ProviderSseParser(maxLineBytes, maxEventBytes, maxEvents, config.getMaxResponseBytes());
    }

    /**
     * 执行调用：{@code listener} 为 null 时同步读取完整响应体，否则按 SSE 流式解析并回调监听器。
     *
     * @param request  HTTP 请求，流式调用需声明接受 {@code text/event-stream}
     * @param context  调用上下文，提供租户、超时、取消与 trace 信息
     * @param listener 事件监听器；为 null 时执行同步调用
     * @return 同步调用返回包含完整响应体的响应；流式调用成功时响应体为空、非 2xx 时携带错误响应体
     * @throws UnsupportedOperationException 传入监听器但本实例未配置 SSE 上限（使用了单参构造函数）时
     * @throws ClaraException                端点被拒绝、请求过大、超时、传输失败或触发协议/资源上限错误时
     */
    public ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context, ProviderSseListener listener) {
        if (listener != null && parser == null) {
            throw new UnsupportedOperationException("this transport is not configured for SSE streaming");
        }
        return send(request, context, listener);
    }

    /**
     * 发送请求并按响应处理方式分发：监听器为 null 表示同步读取完整响应体，否则按 SSE 解析。
     *
     * @param request  HTTP 请求
     * @param context  调用上下文
     * @param listener SSE 事件监听器，同步调用时为 null
     * @return HTTP 响应
     */
    private ProviderHttpResponse send(ProviderHttpRequest request, InvocationContext context,ProviderSseListener listener) {
        if (request == null || context == null) {
            throw new IllegalArgumentException("request and context must not be null");
        }
        context.getCancellationToken().throwIfCancellationRequested(context.getTraceId());
        if (!config.allows(request.getEndpoint())) {
            throw ExceptionUtils.endpointDenied();
        }
        byte[] body = request.getBody();
        if (body.length > config.getMaxRequestBytes()) {
            throw ExceptionUtils.requestTooLarge();
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) request.getEndpoint().toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(effectiveTimeout(context, config.getConnectTimeoutMillis()));
            connection.setReadTimeout(effectiveTimeout(context, config.getReadTimeoutMillis()));
            connection.setRequestMethod(request.getMethod());
            connection.setUseCaches(false);
            for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (body.length > 0) {
                write(connection, body);
            }
            int status = connection.getResponseCode();
            checkContext(context);
            Map<String, List<String>> headers = headers(connection);
            if (listener != null) {
                return stream(status, headers, connection, context, listener);
            }
            // 同步：2xx 读正常流，其余读错误流，非 2xx 也作为结果返回由调用方判断
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] responseBody = input == null ? new byte[0] : read(input, config.getMaxResponseBytes(), context);
            return new ProviderHttpResponse(status, headers, responseBody);
        } catch (SocketTimeoutException exception) {
            throw ExceptionUtils.requestTimeout();
        } catch (ClaraException exception) {
            throw exception;
        } catch (IOException exception) {
            throw ExceptionUtils.transportIoError();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * 处理流式响应：非 2xx 读取错误响应体返回，2xx 校验内容类型后交给 SSE 解析器回调监听器。
     *
     * @param status     响应状态码
     * @param headers    响应头
     * @param connection 已获得响应的连接
     * @param context    调用上下文
     * @param listener   事件监听器
     * @return HTTP 结果，成功时响应体为空
     * @throws IOException 读取错误响应体失败时
     */
    private ProviderHttpResponse stream(int status,
                                        Map<String, List<String>> headers,
                                        HttpURLConnection connection, InvocationContext context,
                                        ProviderSseListener listener) throws IOException {
        if (status < 200 || status >= 300) {
            InputStream error = connection.getErrorStream();
            byte[] errorBody = error == null ? new byte[0] : read(error, config.getMaxResponseBytes(), context);
            return new ProviderHttpResponse(status, headers, errorBody);
        }
        if (!isEventStream(connection.getContentType())) {
            throw ExceptionUtils.protocolError();
        }
        InputStream input = connection.getInputStream();
        try {
            parser.parse(input, context, listener);
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
        return new ProviderHttpResponse(status, headers, new byte[0]);
    }

    /**
     * 以固定长度流式模式写出请求体。
     *
     * @param connection 已打开的连接
     * @param body       请求体字节
     * @throws IOException 写出失败时
     */
    private static void write(HttpURLConnection connection, byte[] body) throws IOException {
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        OutputStream output = connection.getOutputStream();
        try {
            output.write(body);
            output.flush();
        } finally {
            output.close();
        }
    }

    /**
     * 读取响应体，并在读取过程中检查取消与体积上限。
     *
     * @param input   响应体输入流，读取结束后由本方法关闭
     * @param maximum 允许读取的最大字节数
     * @param context 调用上下文，用于取消与超时检查
     * @return 已读取的响应体字节
     * @throws IOException 读取失败时
     */
    private static byte[] read(InputStream input, int maximum, InvocationContext context)
            throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) >= 0) {
                checkContext(context);
                if (count == 0) continue;
                if (total > maximum - count) {
                    throw ExceptionUtils.responseTooLarge();
                }
                output.write(buffer, 0, count);
                total += count;
            }
            return output.toByteArray();
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 读取响应头，缺失时返回空映射。
     *
     * @param connection 已获得响应的连接
     * @return 响应头映射
     */
    private static Map<String, List<String>> headers(HttpURLConnection connection) {
        Map<String, List<String>> value = connection.getHeaderFields();
        return value == null ? Collections.<String, List<String>>emptyMap() : value;
    }

    /**
     * 判断内容类型是否为 SSE 媒体类型。
     *
     * @param contentType 响应头中的内容类型，可为 null
     * @return 忽略参数后媒体类型为 {@code text/event-stream} 时返回 true
     */
    private static boolean isEventStream(String contentType) {
        if (contentType == null) {
            return false;
        }
        int separator = contentType.indexOf(';');
        String mediaType = separator < 0 ? contentType : contentType.substring(0, separator);
        return "text/event-stream".equals(mediaType.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 计算实际超时时间，取配置值与上下文剩余时间的较小值。
     *
     * @param context    调用上下文
     * @param configured 配置的超时毫秒数
     * @return 实际生效的超时毫秒数
     */
    private static int effectiveTimeout(InvocationContext context, int configured) {
        long remaining = context.remainingMillis(System.currentTimeMillis());
        if (remaining <= 0L) {
            throw ExceptionUtils.requestTimeout();
        }
        return (int) Math.min((long) Integer.MAX_VALUE,
                Math.max(1L, Math.min((long) configured, remaining)));
    }

    /**
     * 检查是否已取消或已超时。
     *
     * @param context 调用上下文
     */
    private static void checkContext(InvocationContext context) {
        context.getCancellationToken().throwIfCancellationRequested(context.getTraceId());
        if (context.remainingMillis(System.currentTimeMillis()) <= 0L) {
            throw ExceptionUtils.requestTimeout();
        }
    }
}
