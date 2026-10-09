package com.clara.memory.semantic.milvus;

import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;

import java.io.Closeable;
import java.util.List;

/**
 * Milvus 后端抽象：隔离 Milvus SDK 交互，避免向量库细节泄漏到语义记忆接口。
 */
public interface MilvusBackend extends Closeable {

    /**
     * 确保集合存在并返回既有集合规格。
     *
     * @param expected 期望的集合规格，不可为 null
     * @return 既有（或新建）集合规格，非 null
     */
    MilvusCollectionSpec ensureCollection(MilvusCollectionSpec expected);

    /**
     * 写入（新增或覆盖）一批记忆条目。
     *
     * @param collection 集合名称，不可为 null
     * @param entries    记忆条目列表，不可为 null
     */
    void upsert(String collection, List<MemoryEntry> entries);

    /**
     * 按向量相似度检索记忆条目。
     *
     * @param collection 集合名称，不可为 null
     * @param query      查询封装，不可为 null
     * @return 按相似度降序的匹配结果，非 null
     */
    List<MemoryMatch> search(String collection, MilvusBackendQuery query);

    /**
     * 按条目标识删除单个条目。
     *
     * @param collection 集合名称，不可为 null
     * @param entryId    条目标识，不可为 null
     */
    void deleteById(String collection, String entryId);

    /**
     * 按会话删除该会话关联的全部 {@code CONVERSATION} 来源条目。
     *
     * @param collection     集合名称，不可为 null
     * @param conversationId 会话标识，不可为 null
     */
    void deleteByConversation(String collection, String conversationId);

    /**
     * 关闭后端并释放资源。
     */
    @Override
    void close();
}
