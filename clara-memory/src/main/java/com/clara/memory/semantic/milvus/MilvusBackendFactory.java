package com.clara.memory.semantic.milvus;

/**
 * 负责创建Milvus后端实例。
 */
public interface MilvusBackendFactory {
    MilvusBackend create(MilvusSemanticMemoryConfig config);
}
