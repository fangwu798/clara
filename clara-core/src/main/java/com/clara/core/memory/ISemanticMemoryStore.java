package com.clara.core.memory;

import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;

import java.io.Closeable;
import java.util.List;

/**
 * 语义记忆存储接口：统一承接记忆条目的写入、检索、按条目删除与按会话删除。
 *
 * <p>core 定义的契约接口，由 clara-memory 提供 Local 与 Milvus 两种实现；无租户维度。按条目删除支撑技能
 * 热加载时的移除；按会话删除仅删除 {@code CONVERSATION} 来源条目，避免误删全局技能描述。
 */
public interface ISemanticMemoryStore extends Closeable {

    /**
     * 写入（新增或覆盖）一批记忆条目。
     *
     * @param entries 记忆条目列表，不可为 null
     */
    void upsert(List<MemoryEntry> entries);

    /**
     * 按向量相似度检索记忆条目。
     *
     * @param query 查询条件，不可为 null
     * @return 匹配结果列表（按相似度降序），非 null
     */
    List<MemoryMatch> search(SemanticMemoryQuery query);

    /**
     * 按条目标识删除单个记忆条目，支撑技能热加载时的条目移除。
     *
     * @param entryId 条目标识，不可为 null
     */
    void deleteById(String entryId);

    /**
     * 按会话删除该会话关联的全部 {@code CONVERSATION} 来源记忆条目。
     *
     * @param conversationId 会话标识，不可为 null
     */
    void deleteByConversation(String conversationId);
}
