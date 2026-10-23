package com.clara.memory.semantic.spi;

import com.clara.core.memory.ISemanticMemoryStore;

/**
 * 已打开的语义记忆后端：存储实例 + 启动横幅描述。
 */
public final class SemanticMemoryBackend {

    private final ISemanticMemoryStore store;
    private final String description;

    /**
     * 创建一个后端描述。
     *
     * @param store       语义记忆存储，非 null
     * @param description 启动横幅展示的存放说明，非 null
     */
    public SemanticMemoryBackend(ISemanticMemoryStore store, String description) {
        if (store == null || description == null) {
            throw new IllegalArgumentException("store and description must not be null");
        }
        this.store = store;
        this.description = description;
    }

    /**
     * 语义记忆存储。
     *
     * @return 存储实例，非 null
     */
    public ISemanticMemoryStore getStore() {
        return store;
    }

    /**
     * 启动横幅描述，例如 {@code milvus host:port/collection（bm25 全文检索）}。
     *
     * @return 描述文本，非 null
     */
    public String getDescription() {
        return description;
    }
}
