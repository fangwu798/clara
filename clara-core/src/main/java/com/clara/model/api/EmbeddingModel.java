package com.clara.model.api;

/**
 * 提供嵌入模型能力。
 */
public interface EmbeddingModel {
    EmbeddingResponse embed(EmbeddingRequest request);
}
