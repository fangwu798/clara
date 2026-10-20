package com.clara.memory.semantic.local;

import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import com.clara.memory.semantic.SemanticMemoryErrors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地语义记忆存储：进程内保存记忆向量，检索采用暴力扫描 + 余弦相似度，无外部向量库依赖。
 *
 * <p>嵌入模型需全程一致（同维度），构造时声明向量维度，入库与检索都校验维度。
 * {@code deleteByConversation} 仅删除 {@code CONVERSATION} 来源条目。
 */
public final class LocalSemanticMemoryStore implements ISemanticMemoryStore {

    private final int dimension;
    private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    /**
     * 创建本地语义记忆存储。
     *
     * @param dimension 向量维度，必须为正
     * @throws IllegalArgumentException dimension 非正时
     */
    public LocalSemanticMemoryStore(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        this.dimension = dimension;
    }

    @Override
    public void upsert(List<MemoryEntry> values) {
        open();
        if (values == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        if (values.isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (MemoryEntry entry : values) {
            if (entry == null) {
                throw new IllegalArgumentException("entries must not contain null");
            }
            if (entry.getDimension() != dimension) {
                throw SemanticMemoryErrors.configMismatch();
            }
            if (!ids.add(entry.getId())) {
                throw new IllegalArgumentException("duplicate memory id: " + entry.getId());
            }
        }
        for (MemoryEntry entry : values) {
            entries.put(entry.getId(), entry);
        }
    }

    @Override
    public List<MemoryMatch> search(SemanticMemoryQuery query) {
        open();
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (query.getVector().length != dimension) {
            throw SemanticMemoryErrors.configMismatch();
        }
        List<MemoryMatch> matches = new ArrayList<>();
        for (MemoryEntry entry : entries.values()) {
            if (!matches(query, entry)) {
                continue;
            }
            double score = cosine(query.getVector(), entry.getVector());
            if (score >= query.getMinScore()) {
                matches.add(new MemoryMatch(entry, score));
            }
        }
        matches.sort((left, right) -> {
            int byScore = Double.compare(right.getScore(), left.getScore());
            if (byScore != 0) {
                return byScore;
            }
            int byTime = Long.compare(right.getEntry().getCreatedAtEpochMillis(),
                    left.getEntry().getCreatedAtEpochMillis());
            if (byTime != 0) {
                return byTime;
            }
            return left.getEntry().getId().compareTo(right.getEntry().getId());
        });
        if (matches.size() > query.getTopK()) {
            matches = new ArrayList<>(matches.subList(0, query.getTopK()));
        }
        return Collections.unmodifiableList(matches);
    }

    @Override
    public void deleteById(String entryId) {
        open();
        if (entryId == null) {
            throw new IllegalArgumentException("entryId must not be null");
        }
        entries.remove(entryId);
    }

    @Override
    public void deleteByConversation(String conversationId) {
        open();
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        Iterator<Map.Entry<String, MemoryEntry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            MemoryEntry entry = iterator.next().getValue();
            if (entry.getSourceType() == MemorySourceTypeEnum.CONVERSATION
                    && conversationId.equals(entry.getConversationId())) {
                iterator.remove();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        entries.clear();
    }

    private boolean matches(SemanticMemoryQuery query, MemoryEntry entry) {
        if (entry == null || entry.isExpired(query.getNowEpochMillis())) {
            return false;
        }
        if (query.getSourceType() != null && query.getSourceType() != entry.getSourceType()) {
            return false;
        }
        if (query.getConversationId() != null
                && !query.getConversationId().equals(entry.getConversationId())) {
            return false;
        }
        Map<String, String> filters = query.getFilters();
        if (filters != null && !filters.isEmpty()) {
            for (Map.Entry<String, String> filter : filters.entrySet()) {
                String value = entry.getMetadata().get(filter.getKey());
                if (!filter.getValue().equals(value)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void open() {
        if (closed) {
            throw new IllegalStateException("semantic memory store is closed");
        }
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return -1;
        }
        return Math.max(-1, Math.min(1, dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm))));
    }
}
