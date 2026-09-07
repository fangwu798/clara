package com.clara.model.provider.openai;

import java.net.URI;

/**
 * 定义OpenAI媒体的配置。
 */
public final class OpenAiMediaConfig {
    private final URI endpoint;
    private final String modelId;
    private final String secretRef;
    private final int maxTextChars;
    private final int maxInputBytes;
    private final int maxOutputs;

    public OpenAiMediaConfig(URI endpoint, String modelId, String secretRef,
            int maxTextChars, int maxInputBytes, int maxOutputs) {
        if (endpoint == null) { throw new IllegalArgumentException("endpoint must not be null"); }
        this.modelId = required(modelId, "modelId");
        this.secretRef = required(secretRef, "secretRef");
        if (maxTextChars <= 0 || maxInputBytes <= 0 || maxOutputs <= 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
        this.endpoint = endpoint;
        this.maxTextChars = maxTextChars;
        this.maxInputBytes = maxInputBytes;
        this.maxOutputs = maxOutputs;
    }
    public URI getEndpoint() { return endpoint; }
    public String getModelId() { return modelId; }
    public String getSecretRef() { return secretRef; }
    public int getMaxTextChars() { return maxTextChars; }
    public int getMaxInputBytes() { return maxInputBytes; }
    public int getMaxOutputs() { return maxOutputs; }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
