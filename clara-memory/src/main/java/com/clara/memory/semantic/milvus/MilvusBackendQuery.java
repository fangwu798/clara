package com.clara.memory.semantic.milvus;

import com.clara.memory.semantic.api.SemanticMemoryQuery;

public final class MilvusBackendQuery {
    private final SemanticMemoryQuery query;
    private final int candidateLimit;

    public MilvusBackendQuery(SemanticMemoryQuery query, int limit) {
        if (query == null || limit <= 0) throw new IllegalArgumentException();
        this.query = query;
        candidateLimit = limit;
    }

    public SemanticMemoryQuery getQuery() {
        return query;
    }

    public int getCandidateLimit() {
        return candidateLimit;
    }
}
