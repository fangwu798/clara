package com.clara.memory.semantic.milvus;

/**
 * 负责创建Milvus语义记忆存储器实例。
 */
public final class MilvusSemanticMemoryStoreFactory {
    private final MilvusBackendFactory backends;

    public MilvusSemanticMemoryStoreFactory(MilvusBackendFactory backends) {
        if (backends == null) { throw new IllegalArgumentException(); }
        this.backends = backends;
    }

    public MilvusSemanticMemoryStore create(MilvusSemanticMemoryConfig config) {
        MilvusBackend backend = backends.create(config);
        if (backend == null) { throw new IllegalStateException("Milvus backend factory returned null"); }
        return new MilvusSemanticMemoryStore(config, backend);
    }
}
