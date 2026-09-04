package com.clara.model.api;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public final class EmbeddingResponse {
    private final List<float[]> embeddings;
    public EmbeddingResponse(List<float[]> embeddings) {
        if (embeddings == null) throw new IllegalArgumentException("embeddings must not be null");
        this.embeddings = copy(embeddings);
    }
    public List<float[]> getEmbeddings() { return copy(embeddings); }
    private static List<float[]> copy(List<float[]> source) {
        List<float[]> copy = new ArrayList<float[]>(source.size());
        for (float[] embedding : source) {
            if (embedding == null) throw new IllegalArgumentException("embeddings must not contain null");
            copy.add(embedding.clone());
        }
        return Collections.unmodifiableList(copy);
    }
}
