package com.clara.memory.semantic.milvus;

public interface MilvusBackendFactory {
    MilvusBackend create(MilvusSemanticMemoryConfig config);
}
