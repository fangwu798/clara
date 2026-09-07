package com.clara.model.api.media;

import com.clara.kernel.InvocationContext;
import com.clara.model.api.ProviderOptions;
import java.util.List;

/**
 * 封装图像生成操作的输入参数。
 */
public final class ImageGenerationRequest {
    private final String prompt;
    private final List<String> sourceImageAssetIds;
    private final String maskAssetId;
    private final int count;
    private final Integer width;
    private final Integer height;
    private final String aspectRatio;
    private final String quality;
    private final String style;
    private final Long seed;
    private final String negativePrompt;
    private final ProviderOptions providerOptions;
    private final InvocationContext context;

    public ImageGenerationRequest(String prompt, List<String> sourceImageAssetIds, String maskAssetId,
            int count, Integer width, Integer height, String aspectRatio, String quality, String style,
            Long seed, String negativePrompt, ProviderOptions providerOptions, InvocationContext context) {
        this.prompt = MediaRequestValues.required(prompt, "prompt");
        this.sourceImageAssetIds = MediaRequestValues.assetIds(sourceImageAssetIds, "sourceImageAssetIds");
        if (maskAssetId != null) {
            MediaRequestValues.required(maskAssetId, "maskAssetId");
            if (this.sourceImageAssetIds.isEmpty()) { throw new IllegalArgumentException("mask requires a source image"); }
        }
        if (count <= 0) { throw new IllegalArgumentException("count must be positive"); }
        dimensions(width, height, aspectRatio);
        this.maskAssetId = maskAssetId;
        this.count = count;
        this.width = width;
        this.height = height;
        this.aspectRatio = aspectRatio;
        this.quality = quality;
        this.style = style;
        this.seed = seed;
        this.negativePrompt = negativePrompt;
        this.providerOptions = providerOptions;
        if (context == null) { throw new IllegalArgumentException("context must not be null"); }
        this.context = context;
    }
    private static void dimensions(Integer width, Integer height, String aspectRatio) {
        if ((width == null) != (height == null)) { throw new IllegalArgumentException("width and height must be set together"); }
        if (width != null && (width.intValue() <= 0 || height.intValue() <= 0)) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        if (aspectRatio != null) {
            MediaRequestValues.required(aspectRatio, "aspectRatio");
            if (width != null) { throw new IllegalArgumentException("aspectRatio and dimensions are mutually exclusive"); }
        }
    }
    public String getPrompt() { return prompt; }
    public List<String> getSourceImageAssetIds() { return sourceImageAssetIds; }
    public String getMaskAssetId() { return maskAssetId; }
    public int getCount() { return count; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public String getAspectRatio() { return aspectRatio; }
    public String getQuality() { return quality; }
    public String getStyle() { return style; }
    public Long getSeed() { return seed; }
    public String getNegativePrompt() { return negativePrompt; }
    public ProviderOptions getProviderOptions() { return providerOptions; }
    public InvocationContext getContext() { return context; }
    public boolean isEdit() { return !sourceImageAssetIds.isEmpty(); }
    public boolean isMaskEdit() { return maskAssetId != null; }
}
