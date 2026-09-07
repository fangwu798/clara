package com.clara.model.provider.alibaba;

import com.clara.media.MediaType;
import java.net.URI;

/**
 * 定义阿里云原生任务的配置。
 */
public final class AlibabaNativeTaskConfig {
    private final URI submitEndpoint;
    private final URI taskQueryEndpoint;
    private final String modelId;
    private final String secretRef;
    private final MediaType outputType;
    private final String outputMimeType;
    private final int maxPromptChars;
    private final int maxInputImages;

    public AlibabaNativeTaskConfig(URI submitEndpoint, URI taskQueryEndpoint, String modelId,
            String secretRef, MediaType outputType, String outputMimeType,
            int maxPromptChars, int maxInputImages) {
        if (submitEndpoint == null || taskQueryEndpoint == null || outputType == null) {
            throw new IllegalArgumentException("required config must not be null");
        }
        this.modelId = required(modelId, "modelId"); this.secretRef = required(secretRef, "secretRef");
        this.outputMimeType = required(outputMimeType, "outputMimeType");
        if (maxPromptChars <= 0 || maxInputImages < 0) { throw new IllegalArgumentException("limits are invalid"); }
        this.submitEndpoint = submitEndpoint; this.taskQueryEndpoint = taskQueryEndpoint;
        this.outputType = outputType; this.maxPromptChars = maxPromptChars; this.maxInputImages = maxInputImages;
    }
    public URI getSubmitEndpoint() { return submitEndpoint; }
    public URI getTaskQueryEndpoint() { return taskQueryEndpoint; }
    public String getModelId() { return modelId; }
    public String getSecretRef() { return secretRef; }
    public MediaType getOutputType() { return outputType; }
    public String getOutputMimeType() { return outputMimeType; }
    public int getMaxPromptChars() { return maxPromptChars; }
    public int getMaxInputImages() { return maxInputImages; }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException(name + " must not be blank"); }
        return value;
    }
}
