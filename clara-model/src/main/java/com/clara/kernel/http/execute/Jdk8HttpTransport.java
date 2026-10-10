package com.clara.kernel.http.execute;

import com.clara.context.CallContext;
import com.clara.context.ConvContext;
import com.clara.core.model.IRequest;
import com.clara.kernel.api.execute.IHttpExecute;
import com.clara.kernel.api.execute.ISseListener;
import com.clara.exception.ClaraException;
import com.clara.kernel.http.RawHttpFormatter;
import com.clara.dto.model.RawHttpRequest;
import com.clara.dto.model.RawHttpResponse;
import com.clara.kernel.http.TransportConfig;
import com.clara.kernel.http.execute.sse.SseParser;
import com.clara.utils.ExceptionUtils;

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
 * <p>是什么与所属层：本类属于 {@code clara-core} 的 HTTP 执行子包
 * （{@code com.clara.kernel.http.execute}），是 {@link IHttpExecute} 的唯一实现（final 类，不可继承），
 * 不是模型 API 契约；两种调用方式共用同一套请求处理流程——端点白名单校验、请求体上限、连接/读取超时、
 * 取消检查、请求头与请求体写入、非 2xx 错误响应读取；只有拿到响应之后的处理不同：
 * <ul>
 *   <li>同步：读取完整响应体并返回，非 2xx 也正常返回错误体，由调用方按状态码处理；</li>
 *   <li>流式：要求 2xx 且内容类型为 {@code text/event-stream}，把响应体交给
 *       {@link SseParser} 边读边解析并回调 {@link ISseListener}，成功时返回空响应体。</li>
 * </ul>
 *
 * <p>谁创建谁调用：由宿主应用在装配阶段用无参构造器创建（如 {@code clara-model} 的
 * {@code CompatibleStream}），再交给需要发起模型调用的组件；上层通过 {@link IHttpExecute} 的默认方法
 * （{@code execute(IRequest, Class)} / {@code stream(...)}，后者内部用 {@link StreamHandle}）间接调用
 * {@link #execute}。
 *
 * <p>使用方法：装配阶段 {@code new Jdk8HttpTransport()} 一次并复用 → 每次调用由上层传入 {@link IRequest}
 * 与可选的 {@link ISseListener}；平台配置与调用上下文不在此处传入，而是调用时从 {@code ConvContext} 取。
 * 本类没有 close 或释放动作：每次调用自建连接并在 {@code finally} 中 {@code disconnect()}，实例与进程同
 * 生命周期。
 *
 * <p>使用规范：
 * <ul>
 *   <li>实例只保存不可变的 SSE 解析器，线程安全，可被多个调用方共享；每次调用使用独立的连接。</li>
 *   <li>超时取“配置值”与“上下文剩余时间”的较小值，请求级截止时间不会被突破。</li>
 *   <li>失败时统一抛出 {@link ClaraException} 并携带明确的错误码与是否可重试标记（由
 *       {@link ExceptionUtils} 构造）。</li>
 * </ul>
 *
 * <p>失败语义：端点被拒绝、请求过大、超时、传输 IO 失败、响应超限与协议错误都抛出
 * {@link ClaraException}，其中超时与 IO 错误带可重试标记，资源超限与协议错误不建议重试；{@code iRequest}
 * 为 null 抛 {@link IllegalArgumentException}。非 2xx 不抛异常，而是返回带错误响应体的
 * {@link RawHttpResponse}。
 */
public final class Jdk8HttpTransport implements IHttpExecute {

    /**
     * SSE 事件解析器，只读、可复用；本类无参构造即创建，恒非 null。
     */
    private final SseParser parser;


    /**
     * 创建同时支持同步与 SSE 流式调用的传输器。
     */
    public Jdk8HttpTransport() {
        this.parser = new SseParser();
    }

    /**
     * 创建 SSE 解析器，并校验 SSE 上限与响应体上限的关系。
     *
     * @param config        传输配置，不可为 null
     * @param maxLineBytes  单行最大字节数，必须为正
     * @param maxEventBytes 单个事件最大字节数，必须为正且不超过配置的响应体上限
     * @param maxEvents     最大事件数，必须为正
     * @return SSE 事件解析器，非 null
     * @throws IllegalArgumentException 配置为 null，或事件上限超过响应体上限时
     */
    private static SseParser sseParser(TransportConfig config, int maxLineBytes, int maxEventBytes, int maxEvents) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (maxEventBytes > config.getMaxResponseBytes()) {
            throw new IllegalArgumentException("event limit exceeds response limit");
        }
        return new SseParser(maxLineBytes, maxEventBytes, maxEvents, config.getMaxResponseBytes());
    }


    /**
     * 执行调用：{@code listener} 为 null 时同步读取完整响应体，否则按 SSE 流式解析并回调监听器。
     *
     * @param iRequest 请求对象，非 null；其线格式请求由 {@link IRequest#toRequest()} 编码，流式调用需让该
     *                 对象声明接受 {@code text/event-stream}
     * @param listener 事件监听器；为 null 时执行同步调用
     * @return 同步调用返回包含完整响应体的响应；流式调用成功时响应体为空、非 2xx 时携带错误响应体；非 null
     * @throws IllegalArgumentException 请求为 null 时（调用上下文由本类内部经 {@code ConvContext} 创建）
     * @throws ClaraException           端点被拒绝、请求过大、超时、传输失败或触发协议/资源上限错误时
     */
    @Override
    public RawHttpResponse execute(IRequest iRequest, ISseListener listener) {
        if (listener != null && parser == null) {
            throw new UnsupportedOperationException("this transport is not configured for SSE streaming");
        }
        if (iRequest == null) {
            throw new IllegalArgumentException("request and call must not be null");
        }
        TransportConfig config = ConvContext.current().getProviderConfiguration().getTransport();
        CallContext call = ConvContext.current().newCall(config.getReadTimeoutMillis());
        RawHttpRequest request = iRequest.toRequest();
        System.out.println(RawHttpFormatter.format(request));
        call.getCancellationToken().throwIfCancellationRequested(call.getTraceId());

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
            connection.setConnectTimeout(effectiveTimeout(call, config.getConnectTimeoutMillis()));
            connection.setReadTimeout(effectiveTimeout(call, config.getReadTimeoutMillis()));
            connection.setRequestMethod(request.getMethod());
            connection.setUseCaches(false);
            for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (body.length > 0) {
                write(connection, body);
            }
            int status = connection.getResponseCode();
            checkContext(call);
            Map<String, List<String>> headers = headers(connection);

            if (listener != null) {
                return stream(status, headers, connection, call, listener);
            }
            // 同步：2xx 读正常流，其余读错误流，非 2xx 也作为结果返回由调用方判断
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] responseBody = input == null ? new byte[0] : read(input, config.getMaxResponseBytes(), call);
            RawHttpResponse response = new RawHttpResponse(status, headers, responseBody);
            System.out.println("\n");
            System.out.println(RawHttpFormatter.format(response));
            System.out.println("\n");
            return response;
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
     * @param headers    响应头，非 null
     * @param connection 已获得响应的连接，非 null
     * @param call       调用上下文，非 null
     * @param listener   事件监听器，非 null
     * @return HTTP 结果，成功时响应体为空，非 null
     * @throws IOException    读取错误响应体失败时
     * @throws ClaraException 内容类型不是 {@code text/event-stream}，或解析过程触发取消、超时与上限错误时
     */
    private RawHttpResponse stream(int status, Map<String, List<String>> headers, HttpURLConnection connection, CallContext call, ISseListener listener) throws IOException {
        if (status < 200 || status >= 300) {
            TransportConfig config = ConvContext.current().getProviderConfiguration().getTransport();
            InputStream error = connection.getErrorStream();
            byte[] errorBody = error == null ? new byte[0] : read(error, config.getMaxResponseBytes(), call);

            RawHttpResponse response = new RawHttpResponse(status, headers, errorBody);
            System.out.println("\n");
            System.out.println(RawHttpFormatter.format(response));
            System.out.println("\n");
            return response;
        }
        if (!isEventStream(connection.getContentType())) {
            throw ExceptionUtils.protocolError();
        }
        InputStream input = connection.getInputStream();
        try {
            parser.parse(input, call, listener);
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
        return new RawHttpResponse(status, headers, new byte[0]);
    }

    /**
     * 以固定长度流式模式写出请求体。
     *
     * @param connection 已打开的连接，非 null
     * @param body       请求体字节，非 null，长度非零
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
     * @param input   响应体输入流，非 null，读取结束后由本方法关闭
     * @param maximum 允许读取的最大字节数，单位字节，必须为正
     * @param call    调用上下文，用于取消与超时检查，非 null
     * @return 已读取的响应体字节，非 null（无内容时为空数组）
     * @throws IOException    读取失败时
     * @throws ClaraException 读取过程中被取消、超时或响应体超过 maximum 时
     */
    private static byte[] read(InputStream input, int maximum, CallContext call) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) >= 0) {
                checkContext(call);
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
     * @param connection 已获得响应的连接，非 null
     * @return 响应头映射，非 null；服务端未返回任何头时为空映射
     */
    private static Map<String, List<String>> headers(HttpURLConnection connection) {
        Map<String, List<String>> value = connection.getHeaderFields();
        return value == null ? Collections.emptyMap() : value;
    }

    /**
     * 判断内容类型是否为 SSE 媒体类型。
     *
     * @param contentType 响应头中的内容类型，可为 null
     * @return 忽略参数后媒体类型为 {@code text/event-stream} 时返回 true，contentType 为 null 时返回 false
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
     * @param call       调用上下文，非 null
     * @param configured 配置的超时毫秒数，必须为正
     * @return 实际生效的超时毫秒数，最小 1
     * @throws ClaraException 调用上下文已过期（剩余时间不大于 0）时
     */
    private static int effectiveTimeout(CallContext call, int configured) {
        long remaining = call.remainingMillis(System.currentTimeMillis());
        if (remaining <= 0L) {
            throw ExceptionUtils.requestTimeout();
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, Math.min(configured, remaining)));
    }

    /**
     * 检查是否已取消或已超时。
     *
     * @param call 调用上下文，非 null
     * @throws ClaraException 调用已取消或已超出截止时间时
     */
    private static void checkContext(CallContext call) {
        call.getCancellationToken().throwIfCancellationRequested(call.getTraceId());
        if (call.remainingMillis(System.currentTimeMillis()) <= 0L) {
            throw ExceptionUtils.requestTimeout();
        }
    }
}
