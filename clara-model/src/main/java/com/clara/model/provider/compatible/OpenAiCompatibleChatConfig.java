package com.clara.model.provider.compatible;

import com.clara.model.api.ModelProvider;
import java.net.URI;

/**
 * 定义OpenAI兼容对话的配置。
 */
public final class OpenAiCompatibleChatConfig {
    private final ModelProvider provider;
    private final URI endpoint;
    private final String modelId;
    private final String apiProfile;
    private final String secretRef;

    public OpenAiCompatibleChatConfig(ModelProvider provider, URI endpoint, String modelId,
            String apiProfile, String secretRef) {
        if (provider != ModelProvider.OPENAI && provider != ModelProvider.DEEPSEEK
                && provider != ModelProvider.VOLCENGINE && provider != ModelProvider.ALIBABA) {
            throw new IllegalArgumentException("provider does not use the OpenAI-compatible profile");
        }
        if (endpoint == null) { throw new IllegalArgumentException("endpoint must not be null"); }
        this.modelId = required(modelId, "modelId");
        this.apiProfile = required(apiProfile, "apiProfile");
        this.secretRef = required(secretRef, "secretRef");
        this.provider = provider;
        this.endpoint = endpoint;
    }

    public ModelProvider getProvider() { return provider; }
    public URI getEndpoint() { return endpoint; }
    public String getModelId() { return modelId; }
    public String getApiProfile() { return apiProfile; }
    public String getSecretRef() { return secretRef; }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
