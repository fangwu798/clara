package com.clara.model.provider.compatible;

import com.clara.kernel.ClaraException;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.provider.compatible.config.OpenAiCompatibleChatConfig;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.ProviderSseListener;
import com.clara.model.provider.http.impl.ProviderHttpRequest;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.utils.ExceptionUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 承载 OpenAI 兼容对话「同步与流式共用」的编解码与一次往返请求。
 *
 * <p>用途：非流式（{@link OpenAiCompatibleChatModel}）与流式
 * （{@link OpenAiCompatibleStreamingChatModel}）两条链路的差异只在响应侧——前者一次性解码出
 * {@link ChatResponse}，后者把每个 SSE 事件交给 {@link OpenAiCompatibleStreamAccumulator} 累积并推送
 * 事件。其余环节则逐行相同：编码请求体、按租户解析凭据、组装请求头、发送、校验响应与状态码。
 * 这些共用环节集中在本类，两个模型各自只保留自己的响应处理。
 *
 * <p>使用方式（{@code listener} 决定了走同步还是流式，也决定了 {@code Accept} 头）：
 * <pre>{@code
 * // 非流式：listener 传 null，同步读取完整响应后解码
 * ChatResponse response = exchange.decode(
 *         exchange.send(request, exchange.encode(request, false), null).getBody());
 *
 * // 流式：传入监听器，SSE 事件在 transport.execute 内部逐个回调
 * exchange.send(request, exchange.encode(request, true), listener);
 * }</pre>
 *
 * <p>注意：
 * <ul>
 *   <li>请求体由调用方先编码再传入，而不是在 {@link #send} 内编码——流式必须在提交任务之前、在调用线程上
 *       就把非法请求（如平台不支持的媒体内容）挡下来，不能等到执行器线程才暴露；</li>
 *   <li>凭据副本只在 {@link #send} 内部存活，并在 {@code finally} 中清零，调用方拿不到密钥；</li>
 *   <li>凭据不可用、响应为 null、非 2xx 都直接抛 {@link ClaraException}，由调用方决定是
 *       向上抛出（同步）还是转成 {@code ERROR} 事件（流式）；</li>
 *   <li>流式场景下监听器抛出的异常会原样向外传播，从而中断本次读取。</li>
 * </ul>
 */
final class OpenAiCompatibleChatExchange {
    /**
     * 对话配置，提供平台、端点、模型 ID 与凭据引用。
     */
    private final OpenAiCompatibleChatConfig config;
    /**
     * HTTP 传输器，同步走两参重载，流式走带监听器的重载。
     */
    private final ProviderHttpTransport transport;
    /**
     * 凭据解析器，按租户与引用名换取密钥。
     */
    private final ProviderCredentialResolver credentials;
    /**
     * 请求/响应编解码器，协议细节集中在其中。
     */
    private final OpenAiCompatibleChatCodec codec = new OpenAiCompatibleChatCodec();

    /**
     * 创建一次对话调用的共用执行体。
     *
     * @param config      对话配置，不可为 null
     * @param transport   HTTP 传输器，不可为 null
     * @param credentials 凭据解析器，不可为 null
     * @throws IllegalArgumentException 任一依赖为 null 时
     */
    OpenAiCompatibleChatExchange(OpenAiCompatibleChatConfig config, ProviderHttpTransport transport, ProviderCredentialResolver credentials) {
        if (config == null || transport == null || credentials == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.config = config;
        this.transport = transport;
        this.credentials = credentials;
    }

    /**
     * 编码请求体，同步与流式只差 {@code stream} 标记。
     *
     * @param request 对话请求
     * @param stream  是否置 {@code stream=true}
     * @return UTF-8 编码的请求体
     * @throws IllegalArgumentException 平台选项与配置不匹配、模型不一致或消息内容非法时
     * @throws ClaraException           含平台不支持的能力或序列化失败时
     */
    byte[] encode(ChatRequest request, boolean stream) {
        return codec.encode(request, config, stream);
    }

    /**
     * 解码响应体，只处理非流式响应。
     *
     * @param body 响应体字节
     * @return 领域对话响应
     * @throws com.clara.kernel.ClaraException 响应结构不符合协议时
     */
    ChatResponse decode(byte[] body) {
        return codec.decode(body, config);
    }

    /**
     * 解析凭据并发送一次请求，同时完成响应与状态码校验。
     *
     * <p>{@code listener} 为 null 时走同步重载、一次性读取完整响应；非 null 时走带监听器的重载，
     * SSE 事件在读取过程中逐个回调，同时把 {@code Accept} 置为 {@code text/event-stream}。
     *
     * @param request  对话请求，提供调用上下文与租户
     * @param payload  已编码的请求体
     * @param listener SSE 事件监听器，为 null 表示同步调用
     * @return 平台返回的响应
     * @throws ClaraException 凭据不可用、传输失败、响应为 null 或平台返回非 2xx 时
     */
    ProviderHttpResponse send(ChatRequest request, byte[] payload, ProviderSseListener listener) {
        char[] resolved = credentials.resolve(request.getContext().getTenantId(), config.getSecretRef());
        if (resolved == null || resolved.length == 0) {
            throw ExceptionUtils.credentialUnavailable(config.getProvider());
        }
        char[] secret = resolved.clone();
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + new String(secret));
            headers.put("Content-Type", "application/json");
            headers.put("Accept", listener == null ? "application/json" : "text/event-stream");

            ProviderHttpRequest httpRequest = new ProviderHttpRequest("POST", config.getEndpoint(), headers, payload);
            ProviderHttpResponse response = transport.execute(httpRequest, request.getContext(), listener);
            if (response == null) {
                throw ExceptionUtils.protocolError(config.getProvider());
            }
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                throw ExceptionUtils.providerFailure(config.getProvider(), response.getStatusCode());
            }
            return response;
        } finally {
            Arrays.fill(secret, '\0');
        }
    }
}
