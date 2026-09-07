package com.clara.model.api.media;

import com.clara.kernel.InvocationContext;
import com.clara.model.api.ProviderOptions;
import java.util.List;

/**
 * 封装视频生成操作的输入参数。
 */
public final class VideoGenerationRequest {
    private final String prompt;
    private final List<String> referenceImageAssetIds;
    private final String firstFrameAssetId;
    private final String lastFrameAssetId;
    private final Long durationMs;
    private final Integer width;
    private final Integer height;
    private final String aspectRatio;
    private final Integer framesPerSecond;
    private final Long seed;
    private final String negativePrompt;
    private final ProviderOptions providerOptions;
    private final InvocationContext context;

    public VideoGenerationRequest(String prompt, List<String> referenceImageAssetIds,
            String firstFrameAssetId, String lastFrameAssetId, Long durationMs,
            Integer width, Integer height, String aspectRatio, Integer framesPerSecond,
            Long seed, String negativePrompt, ProviderOptions providerOptions, InvocationContext context) {
        this.prompt = MediaRequestValues.required(prompt, "prompt");
        this.referenceImageAssetIds = MediaRequestValues.assetIds(referenceImageAssetIds, "referenceImageAssetIds");
        if (firstFrameAssetId != null) MediaRequestValues.required(firstFrameAssetId, "firstFrameAssetId");
        if (lastFrameAssetId != null) {
            MediaRequestValues.required(lastFrameAssetId, "lastFrameAssetId");
            if (firstFrameAssetId == null) { throw new IllegalArgumentException("last frame requires first frame"); }
        }
        if (durationMs != null && durationMs.longValue() <= 0) { throw new IllegalArgumentException("durationMs must be positive"); }
        if ((width == null) != (height == null)) { throw new IllegalArgumentException("width and height must be set together"); }
        if (width != null && (width.intValue() <= 0 || height.intValue() <= 0)) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        if (aspectRatio != null) {
            MediaRequestValues.required(aspectRatio, "aspectRatio");
            if (width != null) { throw new IllegalArgumentException("aspectRatio and dimensions are mutually exclusive"); }
        }
        if (framesPerSecond != null && framesPerSecond.intValue() <= 0) {
            throw new IllegalArgumentException("framesPerSecond must be positive");
        }
        if (context == null) { throw new IllegalArgumentException("context must not be null"); }
        this.firstFrameAssetId = firstFrameAssetId;
        this.lastFrameAssetId = lastFrameAssetId;
        this.durationMs = durationMs;
        this.width = width;
        this.height = height;
        this.aspectRatio = aspectRatio;
        this.framesPerSecond = framesPerSecond;
        this.seed = seed;
        this.negativePrompt = negativePrompt;
        this.providerOptions = providerOptions;
        this.context = context;
    }
    public String getPrompt() { return prompt; }
    public List<String> getReferenceImageAssetIds() { return referenceImageAssetIds; }
    public String getFirstFrameAssetId() { return firstFrameAssetId; }
    public String getLastFrameAssetId() { return lastFrameAssetId; }
    public Long getDurationMs() { return durationMs; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public String getAspectRatio() { return aspectRatio; }
    public Integer getFramesPerSecond() { return framesPerSecond; }
    public Long getSeed() { return seed; }
    public String getNegativePrompt() { return negativePrompt; }
    public ProviderOptions getProviderOptions() { return providerOptions; }
    public InvocationContext getContext() { return context; }
}
