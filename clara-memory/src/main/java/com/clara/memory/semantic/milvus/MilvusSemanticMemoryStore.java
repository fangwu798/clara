package com.clara.memory.semantic.milvus;

import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.memory.semantic.SemanticMemoryErrors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Milvus 语义记忆存储：把语义记忆读写委托给 {@link MilvusBackend}，并统一执行集合初始化、
 * 维度校验与结果排序。
 *
 * <p>向量检索（含过滤与相似度计算）由后端完成；本类负责构造期集合校验与检索后的最终排序 / topK 裁剪。
 *
 * <p>BM25 模式（{@link MilvusCollectionSpec#isBm25()}）下集合里没有密集向量，维度校验一律跳过，
 * 改要求查询带上原始文本（{@link SemanticMemoryQuery#getQueryText()}）。
 * {@code deleteByConversation} 仅删除 {@code CONVERSATION} 来源条目，由后端保证该过滤语义。
 */
public final class MilvusSemanticMemoryStore implements ISemanticMemoryStore {

    private final MilvusCollectionSpec spec;
    private final MilvusBackend backend;
    private volatile boolean closed = false;

    /**
     * 创建 Milvus 语义记忆存储，并在构造期确保集合存在且与规格一致。
     *
     * @param spec    集合规格，不可为 null
     * @param backend Milvus 后端，不可为 null
     * @throws IllegalArgumentException spec 或 backend 为 null 时
     */
    public MilvusSemanticMemoryStore(MilvusCollectionSpec spec, MilvusBackend backend) {
        if (spec == null || backend == null) {
            throw new IllegalArgumentException("spec and backend must not be null");
        }
        this.spec = spec;
        this.backend = backend;
        MilvusCollectionSpec actual = backend.ensureCollection(spec);
        if (!spec.sameIdentity(actual)) {
            throw SemanticMemoryErrors.configMismatch();
        }
    }

    @Override
    public void upsert(List<MemoryEntry> entries) {
        open();
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        if (entries.isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (MemoryEntry entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException("entries must not contain null");
            }
            // BM25 模式不存向量，维度对不上也无所谓；向量模式则必须严格一致，否则检索结果毫无意义
            if (!spec.isBm25() && entry.getDimension() != spec.getDimension()) {
                throw SemanticMemoryErrors.configMismatch();
            }
            if (!ids.add(entry.getId())) {
                throw new IllegalArgumentException("duplicate memory id: " + entry.getId());
            }
        }
        try {
            backend.upsert(spec.getCollection(), entries);
        } catch (MilvusBackendException e) {
            throw SemanticMemoryErrors.backendFailed(e.isRetryable());
        }
    }

    @Override
    public List<MemoryMatch> search(SemanticMemoryQuery query) {
        open();
        validateQuery(query);
        List<MemoryMatch> matches = searchBackend(query);
        if (matches == null) {
            return Collections.emptyList();
        }
        List<MemoryMatch> filtered = filterMatches(matches, query.getMinScore());
        filtered.sort(MilvusSemanticMemoryStore::compareMatches);
        if (filtered.size() > query.getTopK()) {
            filtered = new ArrayList<>(filtered.subList(0, query.getTopK()));
        }
        return Collections.unmodifiableList(filtered);
    }

    private void validateQuery(SemanticMemoryQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (spec.isBm25()) {
            // BM25 用原文检索：缺原文说明调用方漏传，早报错好过静默返回空结果
            if (query.getQueryText() == null) {
                throw new IllegalArgumentException("bm25 search requires the raw query text");
            }
        } else if (query.getVector().length != spec.getDimension()) {
            throw SemanticMemoryErrors.configMismatch();
        }
    }

    private List<MemoryMatch> searchBackend(SemanticMemoryQuery query) {
        try {
            return backend.search(spec.getCollection(), new MilvusBackendQuery(query, query.getTopK()));
        } catch (MilvusBackendException e) {
            throw SemanticMemoryErrors.backendFailed(e.isRetryable());
        }
    }

    private static List<MemoryMatch> filterMatches(List<MemoryMatch> matches, double minScore) {
        List<MemoryMatch> filtered = new ArrayList<>();
        for (MemoryMatch match : matches) {
            if (match != null && match.getScore() >= minScore) {
                filtered.add(match);
            }
        }
        return filtered;
    }

    private static int compareMatches(MemoryMatch left, MemoryMatch right) {
        int byScore = Double.compare(right.getScore(), left.getScore());
        if (byScore != 0) {
            return byScore;
        }
        int byTime = Long.compare(right.getEntry().getCreatedAtEpochMillis(),
                left.getEntry().getCreatedAtEpochMillis());
        return byTime != 0 ? byTime : left.getEntry().getId().compareTo(right.getEntry().getId());
    }

    @Override
    public void deleteById(String entryId) {
        open();
        if (entryId == null) {
            throw new IllegalArgumentException("entryId must not be null");
        }
        try {
            backend.deleteById(spec.getCollection(), entryId);
        } catch (MilvusBackendException e) {
            throw SemanticMemoryErrors.backendFailed(e.isRetryable());
        }
    }

    @Override
    public void deleteByConversation(String conversationId) {
        open();
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        try {
            backend.deleteByConversation(spec.getCollection(), conversationId);
        } catch (MilvusBackendException e) {
            throw SemanticMemoryErrors.backendFailed(e.isRetryable());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            backend.close();
        } catch (Exception e) {
            throw SemanticMemoryErrors.backendFailed(false);
        }
    }

    private void open() {
        if (closed) {
            throw new IllegalStateException("semantic memory store is closed");
        }
    }
}
