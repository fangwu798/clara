package com.clara.core.transport;

import com.clara.core.transport.sse.SseParserConfig;
import lombok.Data;

import java.util.Map;

/**
 * 一次 HTTP 传输的不可变策略：端点准入、超时、体积上限、默认请求头与 SSE 上限。
 */
@Data
public final class HttpTransportPolicy {

    /**
     * 决定允许访问的主机与协议。
     */
    private final IEndpointPolicy endpointPolicy;

    /**
     * 连接超时(毫秒)
     */
    private final int connectTimeoutMillis;

    /**
     * 响应超时(毫秒)
     */
    private final int readTimeoutMillis;

    /**
     * 请求体最大字节数
     */
    private final int maxRequestBytes;

    /**
     * 响应体最大字节数
     */
    private final int maxResponseBytes;

    /**
     * 默认携带请求头。
     */
    private final Map<String, String> defaultHeaders;

    /**
     * SSE 解析配置
     */
    private final SseParserConfig sseParserConfig;

    /**
     * 创建传输策略。
     *
     * @param endpointPolicy       端点准入策略，不可为 null
     * @param connectTimeoutMillis 连接超时，必须为正
     * @param readTimeoutMillis    读取超时，必须为正
     * @param maxRequestBytes      请求体上限，必须为正
     * @param maxResponseBytes     响应体上限，必须为正
     * @param defaultHeaders       默认请求头，可为 null
     * @param sseParserConfig      SSE 配置，不可为 null
     */
    public HttpTransportPolicy(IEndpointPolicy endpointPolicy, int connectTimeoutMillis, int readTimeoutMillis, int maxRequestBytes, int maxResponseBytes, Map<String, String> defaultHeaders, SseParserConfig sseParserConfig) {
        if (endpointPolicy == null || sseParserConfig == null) {
            throw new IllegalArgumentException("policy values must not be null");
        }
        if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0 || maxRequestBytes <= 0 || maxResponseBytes <= 0) {
            throw new IllegalArgumentException("transport limits must be positive");
        }
        if (sseParserConfig.getMaxTotalBytes() > maxResponseBytes) {
            throw new IllegalArgumentException("sse total limit exceeds response limit");
        }
        this.endpointPolicy = endpointPolicy;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.maxRequestBytes = maxRequestBytes;
        this.maxResponseBytes = maxResponseBytes;
        this.defaultHeaders = HttpHeaders.validate(defaultHeaders);
        this.sseParserConfig = sseParserConfig;
    }

}
