package com.clara.dto.memory;

import com.clara.enums.memory.MemorySourceTypeEnum;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语义记忆查询条件：描述一次向量检索的过滤与召回参数。
 *
 * <p>检索对话记录时按 {@link #conversationId()} 限定当前会话；检索技能描述时通过 {@link #sourceType()}
 * 限定来源类型。无租户：不含 {@code tenantId}。
 */
public final class SemanticMemoryQuery {

    private final MemorySourceTypeEnum sourceType;
    private final String conversationId;
    private final float[] vector;
    private final int topK;
    private final double minScore;
    private final long nowEpochMillis;
    private final Map<String, String> filters;

    /**
     * 创建查询条件。
     *
     * @param sourceType     来源类型过滤，可为 null（不限来源）
     * @param conversationId 会话标识过滤，可为 null（不限会话，如检索技能描述）
     * @param vector         查询向量，不可为 null、空、含 NaN/Inf 或零范数
     * @param topK           召回条数，必须为正
     * @param minScore       最小相似度，须在 {@code [-1, 1]} 区间
     * @param nowEpochMillis 当前时间（Unix epoch 毫秒），用于过期判断，不可为负
     * @param filters        元数据过滤条件，可为 null
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public SemanticMemoryQuery(MemorySourceTypeEnum sourceType, String conversationId, float[] vector,
            int topK, double minScore, long nowEpochMillis, Map<String, String> filters) {
        if (vector == null || vector.length == 0 || topK <= 0 || Double.isNaN(minScore)
                || minScore < -1 || minScore > 1 || nowEpochMillis < 0) {
            throw new IllegalArgumentException("semantic query is invalid");
        }
        this.sourceType = sourceType;
        this.conversationId = conversationId;
        this.vector = vector.clone();
        double norm = 0;
        for (float value : this.vector) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalArgumentException("query vector is invalid");
            }
            norm += (double) value * value;
        }
        if (norm == 0) {
            throw new IllegalArgumentException("query vector must not be zero");
        }
        this.topK = topK;
        this.minScore = minScore;
        this.nowEpochMillis = nowEpochMillis;
        LinkedHashMap<String, String> copied = new LinkedHashMap<String, String>();
        if (filters != null) {
            for (Map.Entry<String, String> value : filters.entrySet()) {
                if (value.getKey() == null || value.getValue() == null) {
                    throw new IllegalArgumentException("metadata filter is invalid");
                }
                copied.put(value.getKey(), value.getValue());
            }
        }
        this.filters = Collections.unmodifiableMap(copied);
    }

    public MemorySourceTypeEnum getSourceType() {
        return sourceType;
    }

    public String getConversationId() {
        return conversationId;
    }

    public float[] getVector() {
        return vector.clone();
    }

    public int getTopK() {
        return topK;
    }

    public double getMinScore() {
        return minScore;
    }

    public long getNowEpochMillis() {
        return nowEpochMillis;
    }

    public Map<String, String> getMetadataFilters() {
        return filters;
    }
}
