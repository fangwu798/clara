package com.clara.model.api;

public interface EmbeddingModel {
    EmbeddingResponse embed(EmbeddingRequest request);
}
