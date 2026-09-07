package com.clara.model.api.media;

/**
 * 提供图像生成模型能力。
 */
public interface ImageGenerationModel {
    ImageGenerationResult generate(ImageGenerationRequest request);
}
