package com.clara.model.provider.volcengine;

import java.net.URI;

/**
 * 定义火山引擎视频的配置。
 */
public final class VolcengineVideoConfig {
    private final URI tasksEndpoint;
    private final String modelId;
    private final String secretRef;
    private final int maxPromptChars;
    private final int maxReferenceImages;

    public VolcengineVideoConfig(URI tasksEndpoint, String modelId, String secretRef,
            int maxPromptChars, int maxReferenceImages) {
        if (tasksEndpoint == null) { throw new IllegalArgumentException("tasksEndpoint must not be null"); }
        this.modelId = required(modelId, "modelId"); this.secretRef = required(secretRef, "secretRef");
        if (maxPromptChars <= 0 || maxReferenceImages < 0) { throw new IllegalArgumentException("limits are invalid"); }
        this.tasksEndpoint = tasksEndpoint; this.maxPromptChars = maxPromptChars;
        this.maxReferenceImages = maxReferenceImages;
    }
    public URI getTasksEndpoint() { return tasksEndpoint; }
    public String getModelId() { return modelId; }
    public String getSecretRef() { return secretRef; }
    public int getMaxPromptChars() { return maxPromptChars; }
    public int getMaxReferenceImages() { return maxReferenceImages; }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException(name + " must not be blank"); }
        return value;
    }
}
