package com.clara.model.provider.compatible;

import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.provider.compatible.config.OpenAiCompatibleChatConfig;
import com.clara.model.provider.http.ProviderExchange;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.http.impl.ProviderInvoker;

import java.net.URI;

/**
 * 描述一次 OpenAI 兼容对话往返：打到哪、发什么、期望什么响应类型、如何解析。
 *
 * <p>用途：把对话在「一次往返」上的差异收敛成一个描述对象——请求体在调用线程上先由
 * {@link OpenAiCompatibleChatCodec} 编码好（非法请求同步抛出，不会变成异步错误事件），端点取自配置，
 * {@code Accept} 由 {@link #streaming} 决定，响应按非流式结构解析。发送、鉴权与状态码映射由
 * {@link ProviderInvoker} 统一负责。
 *
 * <p>同步与流式共用本类：流式只用到 {@link #accept} 与端点、请求体，事件在传输过程中由监听器消费，
 * 因此不会调用 {@link #decode}。
 *
 * <p>注意：实例是一次调用一个、不可变；{@code payload} 由调用方传入且不做拷贝，构造后不应再被修改。
 */
final class OpenAiCompatibleChatExchange implements ProviderExchange<ChatRequest, ChatResponse> {
    /**
     * 对话配置，提供端点、平台与协议档位。
     */
    private final OpenAiCompatibleChatConfig config;
    /**
     * 协议编解码器，负责把非流式响应体还原为领域响应。
     */
    private final OpenAiCompatibleChatCodec codec;
    /**
     * 已编码的请求体。
     */
    private final byte[] payload;
    /**
     * 是否为流式调用，决定 Accept 头。
     */
    private final boolean streaming;

    /**
     * 创建一次对话往返描述。
     *
     * @param config    对话配置
     * @param codec     协议编解码器
     * @param payload   已编码的请求体
     * @param streaming 是否流式调用
     */
    OpenAiCompatibleChatExchange(OpenAiCompatibleChatConfig config, OpenAiCompatibleChatCodec codec,
            byte[] payload, boolean streaming) {
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

    public ChatResponse decode(ProviderHttpResponse response, ChatRequest request) {
        return codec.decode(response.getBody(), config);
    }
}
