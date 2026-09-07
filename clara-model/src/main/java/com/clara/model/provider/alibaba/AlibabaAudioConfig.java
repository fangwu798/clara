package com.clara.model.provider.alibaba;

import java.net.URI;

/**
 * 定义阿里云音频的配置。
 */
public final class AlibabaAudioConfig {
    private final URI endpoint;
    private final String modelId;
    private final String secretRef;
    private final int maxTextChars;

    public AlibabaAudioConfig(URI endpoint, String modelId, String secretRef, int maxTextChars) {
        if (endpoint == null) { throw new IllegalArgumentException("endpoint must not be null"); }
        this.modelId = required(modelId, "modelId"); this.secretRef = required(secretRef, "secretRef");
        if (maxTextChars <= 0) { throw new IllegalArgumentException("maxTextChars must be positive"); }
        this.endpoint = endpoint; this.maxTextChars = maxTextChars;
    }
    public URI getEndpoint() { return endpoint; }
    public String getModelId() { return modelId; }
    public String getSecretRef() { return secretRef; }
    public int getMaxTextChars() { return maxTextChars; }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException(name + " must not be blank"); }
        return value;
    }
}
