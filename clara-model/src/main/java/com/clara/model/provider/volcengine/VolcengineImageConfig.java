package com.clara.model.provider.volcengine;

import java.net.URI;

/**
 * 定义火山引擎图像的配置。
 */
public final class VolcengineImageConfig {
    private final URI endpoint;
    private final String modelId;
    private final String secretRef;
    private final int maxPromptChars;
    private final int maxImages;
    private final int maxSourceImages;
    private final boolean watermark;

    public VolcengineImageConfig(URI endpoint, String modelId, String secretRef,
            int maxPromptChars, int maxImages, int maxSourceImages, boolean watermark) {
        if (endpoint == null) { throw new IllegalArgumentException("endpoint must not be null"); }
        this.modelId = required(modelId, "modelId");
        this.secretRef = required(secretRef, "secretRef");
        if (maxPromptChars <= 0 || maxImages <= 0 || maxSourceImages < 0) {
            throw new IllegalArgumentException("limits are invalid");
        }
        this.endpoint = endpoint;
        this.maxPromptChars = maxPromptChars;
        this.maxImages = maxImages;
        this.maxSourceImages = maxSourceImages;
        this.watermark = watermark;
    }
    public URI getEndpoint() { return endpoint; }
    public String getModelId() { return modelId; }
    public String getSecretRef() { return secretRef; }
    public int getMaxPromptChars() { return maxPromptChars; }
    public int getMaxImages() { return maxImages; }
    public int getMaxSourceImages() { return maxSourceImages; }
    public boolean isWatermark() { return watermark; }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
