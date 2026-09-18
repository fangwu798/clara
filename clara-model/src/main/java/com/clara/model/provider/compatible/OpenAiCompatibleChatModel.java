package com.clara.model.provider.compatible;

import com.clara.kernel.ClaraException;
import com.clara.model.api.ChatModel;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.provider.compatible.config.OpenAiCompatibleChatConfig;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.impl.ProviderInvoker;
import com.clara.model.utils.ExceptionUtils;

/**
 * 提供 OpenAI 兼容协议的对话模型能力（同步调用）。
 *
 * <p>用途：把 {@link ChatRequest} 编码为 OpenAI 兼容的 {@code /chat/completions} 请求体，经
 * {@link ProviderHttpTransport} 同步发出并解码响应。支持文本、工具声明、工具调用与工具调用结果消息，
 * 并对响应结构做严格校验；OpenAI、DeepSeek、火山引擎、阿里云等平台共用本实现，差异由
 * {@link OpenAiCompatibleChatConfig} 中的平台与协议档位决定。
 *
 * <p>与流式的分工：编码由 {@link OpenAiCompatibleChatCodec} 负责，凭据解析、请求头、发送与状态码映射由
 * {@link ProviderInvoker} 统一承担，本类只描述「这一次往返打哪个端点、发哪份请求体、如何解析响应」。
 * 流式调用见 {@link OpenAiCompatibleStreamingChatModel}。
 *
 * <p>使用方式：
 * <pre>{@code
 * ChatModel model = new OpenAiCompatibleChatModel(config, transport, provider.credentialResolver());
 * ChatResponse response = model.chat(request);
 * }</pre>
 *
 * <p>注意：凭据在每次调用时按租户解析，并在 {@code finally} 中清零副本；响应非 2xx 时映射为
 * {@code MODEL_PROVIDER_FAILED}（401/403/429 分别归入鉴权、授权、限流，429 与 5xx 标记为可重试）；
 * 请求内容或响应结构不符合协议时抛 {@code MODEL_PROTOCOL_ERROR}。异常统一由 {@link ExceptionUtils}
 * 构造，本类不自行拼装错误码与错误类别。
 */
public final class OpenAiCompatibleChatModel implements ChatModel {
    /**
     * 对话配置，提供平台、端点、模型 ID 与凭据引用。
     */
    private final OpenAiCompatibleChatConfig config;
    /**
     * 统一的往返执行器，负责凭据、请求头、发送与状态码校验。
     */
    private final ProviderInvoker invoker;
    /**
     * 协议编解码器，协议细节集中在其中。
     */
    private final OpenAiCompatibleChatCodec codec = new OpenAiCompatibleChatCodec();

    /**
     * 创建对话模型。
     *
     * @param config      对话配置，不可为 null
     * @param transport   HTTP 传输器，不可为 null
     * @param credentials 凭据解析器，不可为 null
     * @throws IllegalArgumentException 任一依赖为 null 时
     */
    public OpenAiCompatibleChatModel(OpenAiCompatibleChatConfig config, ProviderHttpTransport transport, ProviderCredentialResolver credentials) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.invoker = new ProviderInvoker(config.getProvider(), config.getSecretRef(), transport, credentials);
    }

    /**
     * 执行一次同步对话调用。
     *
     * <p>流程：在调用线程编码请求体（非法请求同步抛出）→ 由 {@link ProviderInvoker} 解析凭据、发送并
     * 校验状态码 → 解码响应；凭据副本在发送过程中清零，本类不接触密钥。
     *
     * @param request 对话请求，包含消息、参数与调用上下文，不可为 null
     * @return 平台返回的对话响应
     * @throws IllegalArgumentException 请求为 null，或消息内容不符合协议（如平台不支持的媒体内容）时
     * @throws ClaraException           凭据不可用、请求被取消、传输失败、平台返回非 2xx 或响应结构非法时
     */
    public ChatResponse chat(ChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        byte[] payload = codec.encode(request, config, false);
        return invoker.exchange(request,
                request.getContext(),
                new OpenAiCompatibleChatExchange(config, codec, payload, false)
        );
    }
}
