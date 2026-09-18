package com.clara.model.provider.anthropic;

import com.clara.model.api.ChatModel;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.ModelProvider;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.impl.ProviderInvoker;
import com.clara.model.utils.ExceptionUtils;

/**
 * 提供Anthropic对话模型能力。
 *
 * <p>用途：在调用线程把请求编码为 Messages 请求体，再由 {@link ProviderInvoker} 统一完成凭据解析、
 * 鉴权头（{@code x-api-key} 与 {@code anthropic-version}）、发送与状态码映射，最后解码响应；
 * 平台差异集中在 {@link AnthropicChatExchange} 里描述。
 *
 * <p>注意：凭据在每次调用时按租户解析，并在 {@code finally} 中清零副本；响应非 2xx 时映射为
 * {@code MODEL_PROVIDER_FAILED}（401/403/429 分别归入鉴权、授权、限流，429 与 5xx 标记为可重试）。
 * 异常统一由 {@link ExceptionUtils} 构造，本类不自行拼装错误码与错误类别。
 */
public final class AnthropicChatModel implements ChatModel {
    private final AnthropicChatConfig config;
    private final ProviderInvoker invoker;
    private final AnthropicChatCodec codec = new AnthropicChatCodec();

    public AnthropicChatModel(AnthropicChatConfig config, ProviderHttpTransport transport,
            ProviderCredentialResolver credentials) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.invoker = new ProviderInvoker(ModelProvider.ANTHROPIC, config.getSecretRef(), transport, credentials);
    }

    public ChatResponse chat(ChatRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        byte[] payload = codec.encode(request, config);
        return invoker.exchange(request, request.getContext(),
                new AnthropicChatExchange(config, codec, payload, false));
    }
}
