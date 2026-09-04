package com.clara.model.provider.anthropic;

import java.net.URI;

public final class AnthropicChatConfig {
    private final URI endpoint;
    private final String modelId;
    private final String apiProfile;
    private final String apiVersion;
    private final String secretRef;
    private final int defaultMaxTokens;

    public AnthropicChatConfig(URI endpoint, String modelId, String apiProfile,
            String apiVersion, String secretRef, int defaultMaxTokens) {
        if (endpoint == null) throw new IllegalArgumentException("endpoint must not be null");
        this.modelId = required(modelId, "modelId");
        this.apiProfile = required(apiProfile, "apiProfile");
        this.apiVersion = required(apiVersion, "apiVersion");
        this.secretRef = required(secretRef, "secretRef");
        if (defaultMaxTokens <= 0)
            throw new IllegalArgumentException("defaultMaxTokens must be positive");
        this.endpoint = endpoint;
        this.defaultMaxTokens = defaultMaxTokens;
    }

    public URI getEndpoint() { return endpoint; }
    public String getModelId() { return modelId; }
    public String getApiProfile() { return apiProfile; }
    public String getApiVersion() { return apiVersion; }
    public String getSecretRef() { return secretRef; }
    public int getDefaultMaxTokens() { return defaultMaxTokens; }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
