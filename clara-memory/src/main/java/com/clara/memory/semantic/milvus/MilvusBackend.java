package com.clara.memory.semantic.milvus;

import com.clara.memory.semantic.api.MemoryEntry;

import java.io.Closeable;
import java.util.List;

/**
 * 定义。
 */
public interface MilvusBackend extends Closeable {
    MilvusCollectionSpec ensureCollection(MilvusCollectionSpec expected);

    void upsert(String collection, List<MemoryEntry> entries);

    List<MemoryEntry> search(String collection, MilvusBackendQuery query);

    void deleteByConversation(String collection, String tenantId, String conversationId);
}
