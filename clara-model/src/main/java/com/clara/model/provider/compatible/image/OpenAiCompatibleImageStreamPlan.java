package com.clara.model.provider.compatible.image;

import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.provider.http.ProviderRequestPlan;

import java.net.URI;

/**
 * 描述一次 OpenAI 兼容流式生图的请求侧：打到哪、发什么、期望事件流。
 *
 * <p>用途：流式生图的响应体在读取过程中就被消费（每个事件立刻落盘并回调），不存在「读完再解析」的响应，
 * 因此只需要 {@link ProviderRequestPlan}；鉴权、请求头合并、发送与状态码映射由
 * {@code ProviderInvoker} 统一负责。
 *
 * <p>注意：请求体在调用线程上先由 {@link OpenAiCompatibleImageCodec} 编码好（非法请求同步抛出，不会变成
 * 异步错误事件）；{@code payload} 不做拷贝，构造后不应再被修改。
 */
final class OpenAiCompatibleImageStreamPlan implements ProviderRequestPlan<ImageGenerationRequest> {
    /**
     * 图片配置，提供端点。
     */
    private final OpenAiCompatibleImageConfig config;
    /**
     * 已编码的请求体。
     */
    private final byte[] payload;

    /**
     * 创建请求描述。
     *
     * @param config  图片配置
     * @param payload 已编码的请求体
     */
    OpenAiCompatibleImageStreamPlan(OpenAiCompatibleImageConfig config, byte[] payload) {
        this.config = config;
        this.payload = payload;
    }

    public URI endpoint(ImageGenerationRequest request) {
        return config.getEndpoint();
    }

    public byte[] payload(ImageGenerationRequest request) {
        return payload;
    }

    public String accept(ImageGenerationRequest request) {
        return "text/event-stream";
    }
}
