package com.clara.model.provider.anthropic;

import com.clara.model.api.ChatModel;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.ModelProvider;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.impl.ProviderHttpRequest;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.utils.ExceptionUtils;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提供Anthropic对话模型能力。
 *
 * <p>注意：凭据在每次调用时按租户解析，并在 {@code finally} 中清零副本；响应非 2xx 时映射为
 * {@code MODEL_PROVIDER_FAILED}（401/403/429 分别归入鉴权、授权、限流，429 与 5xx 标记为可重试）。
 * 异常统一由 {@link ExceptionUtils} 构造，本类不自行拼装错误码与错误类别。
 */
public final class AnthropicChatModel implements ChatModel {
    private final AnthropicChatConfig config;
    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentials;
    private final AnthropicChatCodec codec = new AnthropicChatCodec();

    public AnthropicChatModel(AnthropicChatConfig config, ProviderHttpTransport transport,
            ProviderCredentialResolver credentials) {
        if (config == null || transport == null || credentials == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.config = config;
        this.transport = transport;
        this.credentials = credentials;
    }

    public ChatResponse chat(ChatRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        request.getContext().getCancellationToken()
                .throwIfCancellationRequested(request.getContext().getTraceId());
        byte[] payload = codec.encode(request, config);
        char[] resolved = credentials.resolve(request.getContext().getTenantId(), config.getSecretRef());
        if (resolved == null || resolved.length == 0) {
            throw ExceptionUtils.credentialUnavailable(ModelProvider.ANTHROPIC);
        }
        char[] secret = resolved.clone();
        try {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("x-api-key", new String(secret));
            headers.put("anthropic-version", config.getApiVersion());
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "application/json");
            ProviderHttpResponse response = transport.execute(new ProviderHttpRequest("POST",
                    config.getEndpoint(), headers, payload), request.getContext());
            if (response == null) throw ExceptionUtils.protocolError(ModelProvider.ANTHROPIC);
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300)
                throw ExceptionUtils.providerFailure(ModelProvider.ANTHROPIC, response.getStatusCode());
            return codec.decode(response.getBody(), config);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }
}
