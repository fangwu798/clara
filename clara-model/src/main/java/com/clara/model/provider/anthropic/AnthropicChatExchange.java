package com.clara.model.provider.anthropic;

import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.provider.http.ProviderExchange;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.http.impl.ProviderInvoker;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 描述一次 Anthropic Messages 往返：打到哪、发什么、期望什么响应类型、怎么鉴权、如何解析。
 *
 * <p>用途：Anthropic 与 OpenAI 兼容家族在「一次往返」上的差异只有两处——鉴权用 {@code x-api-key} 并附带
 * {@code anthropic-version}，以及响应结构不同；本类把这两处连同端点、请求体、{@code Accept} 一起收敛掉，
 * 发送、状态码映射与凭据清零交给 {@link ProviderInvoker} 统一负责。
 *
 * <p>同步与流式共用本类：流式只用到端点、请求体与 {@link #accept}，事件在传输过程中由监听器消费，
 * 因此不会调用 {@link #decode}。
 *
 * <p>注意：实例是一次调用一个、不可变；{@code payload} 由调用方传入且不做拷贝，构造后不应再被修改。
 */
final class AnthropicChatExchange implements ProviderExchange<ChatRequest, ChatResponse> {
    /**
     * Anthropic 配置，提供端点、协议版本与凭据引用。
     */
    private final AnthropicChatConfig config;
    /**
     * 协议编解码器，负责把非流式响应体还原为领域响应。
     */
    private final AnthropicChatCodec codec;
    /**
     * 已编码的请求体。
     */
    private final byte[] payload;
    /**
     * 是否为流式调用，决定 Accept 头。
     */
    private final boolean streaming;

    /**
     * 创建一次 Anthropic 往返描述。
     *
     * @param config    Anthropic 配置
     * @param codec     协议编解码器
     * @param payload   已编码的请求体
     * @param streaming 是否流式调用
     */
    AnthropicChatExchange(AnthropicChatConfig config, AnthropicChatCodec codec, byte[] payload, boolean streaming) {
        this.config = config;
        this.codec = codec;
        this.payload = payload;
        this.streaming = streaming;
    }

    public URI endpoint(ChatRequest request) {
        return config.getEndpoint();
    }

    public byte[] payload(ChatRequest request) {
        return payload;
    }

    public String accept(ChatRequest request) {
        return streaming ? "text/event-stream" : "application/json";
    }

    public Map<String, String> authentication(String secret, ChatRequest request) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("x-api-key", secret);
        headers.put("anthropic-version", config.getApiVersion());
        return headers;
    }

    public ChatResponse decode(ProviderHttpResponse response, ChatRequest request) {
        return codec.decode(response.getBody(), config);
    }
}
