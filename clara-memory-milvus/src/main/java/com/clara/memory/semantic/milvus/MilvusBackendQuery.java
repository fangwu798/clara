package com.clara.memory.semantic.milvus;

import com.clara.dto.memory.SemanticMemoryQuery;
import lombok.Getter;

/**
 * Milvus 后端查询封装：携带语义查询条件与候选召回上限，供后端构造向量检索。
 */
@Getter
public final class MilvusBackendQuery {

    private final SemanticMemoryQuery query;
    private final int candidateLimit;

    /**
     * 创建 Milvus 后端查询。
     *
     * @param query          语义查询条件，不可为 null
     * @param candidateLimit 候选召回上限，必须为正
     * @throws IllegalArgumentException query 为 null 或 candidateLimit 非正时
     */
    public MilvusBackendQuery(SemanticMemoryQuery query, int candidateLimit) {
        if (query == null || candidateLimit <= 0) {
            throw new IllegalArgumentException("milvus backend query is invalid");
        }
        this.query = query;
        this.candidateLimit = candidateLimit;
    }

}
