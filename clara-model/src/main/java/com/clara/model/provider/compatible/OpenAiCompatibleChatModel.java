package com.clara.model.provider.compatible;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.model.api.ChatModel;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.impl.ProviderHttpRequest;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提供OpenAI兼容对话模型能力。
 */
public final class OpenAiCompatibleChatModel implements ChatModel {
    private final OpenAiCompatibleChatConfig config;
    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentials;
    private final OpenAiCompatibleChatCodec codec;

    public OpenAiCompatibleChatModel(OpenAiCompatibleChatConfig config,
            ProviderHttpTransport transport, ProviderCredentialResolver credentials) {
        if (config == null || transport == null || credentials == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.config = config;
        this.transport = transport;
        this.credentials = credentials;
        this.codec = new OpenAiCompatibleChatCodec();
    }

    public ChatResponse chat(ChatRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        request.getContext().getCancellationToken()
                .throwIfCancellationRequested(request.getContext().getTraceId());
        byte[] payload = codec.encode(request, config);
        char[] resolved = credentials.resolve(request.getContext().getTenantId(), config.getSecretRef());
        if (resolved == null || resolved.length == 0) credentialUnavailable();
        char[] secret = resolved.clone();
        try {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", "Bearer " + new String(secret));
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "application/json");
            ProviderHttpRequest httpRequest = new ProviderHttpRequest("POST", config.getEndpoint(),
                    headers, payload);
            ProviderHttpResponse response = transport.execute(httpRequest, request.getContext());
            if (response == null) protocol();
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300)
                providerFailure(response.getStatusCode());
            return codec.decode(response.getBody(), config);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private void credentialUnavailable() {
        throw error(ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE, ErrorCategory.CONFIGURATION, false, null);
    }
    private void protocol() {
        throw error(ErrorCodes.MODEL_PROTOCOL_ERROR, ErrorCategory.EXTERNAL_SERVICE, false, null);
    }
    private void providerFailure(int status) {
        ErrorCategory category = status == 401 ? ErrorCategory.AUTHENTICATION
                : status == 403 ? ErrorCategory.AUTHORIZATION
                : status == 429 ? ErrorCategory.RATE_LIMIT : ErrorCategory.EXTERNAL_SERVICE;
        boolean retryable = status == 429 || status >= 500;
        throw error(ErrorCodes.MODEL_PROVIDER_FAILED, category, retryable, String.valueOf(status));
    }
    private ClaraException error(String code, ErrorCategory category, boolean retryable,
            String providerCode) {
        return new ClaraException(new ClaraError(code, category, code, retryable,
                null, config.getProvider().name(), providerCode, null,
                Collections.<String, String>emptyMap()));
    }
}
