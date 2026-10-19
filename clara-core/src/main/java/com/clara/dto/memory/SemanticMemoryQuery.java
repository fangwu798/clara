package com.clara.dto.memory;

import com.clara.enums.memory.MemorySourceTypeEnum;
import com.clara.utils.StringUtils;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语义记忆查询条件：描述一次向量检索的过滤与召回参数。
 *
 * <p>检索对话记录时按 conversationId() 限定当前会话；检索技能描述时通过 sourceType()
 * 限定来源类型。无租户：不含 {@code tenantId}。
 *
 * <p>{@link #getQueryText()} 携带本次检索的原始文本，供全文检索实现（Milvus BM25）使用；
 * 向量检索并不需要它，为 null 即可。两种检索方式共用同一个查询对象，调用方就不用关心
 * 底层用的是哪一种。
 */
@Data
public final class SemanticMemoryQuery {

    /** 来源类型过滤，null 表示不限来源。 */
    private final MemorySourceTypeEnum sourceType;
    /** 会话标识过滤，null 表示不限会话。 */
    private final String conversationId;
    /** 查询向量，构造时校验并做防御性复制。 */
    private final float[] vector;
    /** 召回条数，必须为正。 */
    private final int topK;
    /** 最小相似度，须在 {@code [-1, 1]} 区间。 */
    private final double minScore;
    /** 当前时间（Unix epoch 毫秒），用于过期判断，不可为负。 */
    private final long nowEpochMillis;
    /** 只读元数据过滤条件副本。 */
    private final Map<String, String> filters;
    /** 原始查询文本，供全文检索使用；空白统一归一为 null。 */
    private final String queryText;

    /**
     * 由构建器创建查询条件并完成校验与防御性复制。
     *
     * @param builder 已填充必填字段的构建器，不能为 null
     * @throws IllegalArgumentException 字段不合法时
     */
    private SemanticMemoryQuery(Builder builder) {
        validateRange(builder.vector, builder.topK, builder.minScore, builder.nowEpochMillis);
        validateVector(builder.vector);
        this.sourceType = builder.sourceType;
        this.conversationId = builder.conversationId;
        this.vector = builder.vector.clone();
        this.topK = builder.topK;
        this.minScore = builder.minScore;
        this.nowEpochMillis = builder.nowEpochMillis;
        this.filters = copyFilters(builder.filters);
        this.queryText = StringUtils.blankToNull(builder.queryText);
    }

    /**
     * 创建语义记忆查询构建器。
     *
     * @param vector         查询向量，不可为 null、空、含 NaN/Inf 或零范数
     * @param topK           召回条数，必须为正
     * @param minScore       最小相似度，须在 {@code [-1, 1]} 区间
     * @param nowEpochMillis 当前时间（Unix epoch 毫秒），用于过期判断，不可为负
     * @return 构建器，非 null
     */
    public static Builder builder(float[] vector, int topK, double minScore, long nowEpochMillis) {
        return new Builder(vector, topK, minScore, nowEpochMillis);
    }

    /**
     * 校验查询向量的存在性及数值范围参数。
     *
     * @param vector         查询向量
     * @param topK           召回条数
     * @param minScore       最小相似度
     * @param nowEpochMillis 当前时间
     * @throws IllegalArgumentException 任意参数不合法时
     */
    private static void validateRange(float[] vector, int topK, double minScore, long nowEpochMillis) {
        if (vector == null || vector.length == 0 || topK <= 0 || Double.isNaN(minScore) || minScore < -1 || minScore > 1 || nowEpochMillis < 0) {
            throw new IllegalArgumentException("semantic query is invalid");
        }
    }

    /**
     * 校验向量元素均为有限值且范数不为零。
     *
     * @param vector 待校验向量
     * @throws IllegalArgumentException 含 NaN/Inf 或零范数时
     */
    private static void validateVector(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalArgumentException("query vector is invalid");
            }
            norm += (double) value * value;
        }
        if (norm == 0) {
            throw new IllegalArgumentException("query vector must not be zero");
        }
    }

    /**
     * 复制元数据过滤条件为只读映射。
     *
     * @param filters 原始过滤条件，可为 null
     * @return 只读过滤条件副本，非 null
     * @throws IllegalArgumentException 键或值为 null 时
     */
    private static Map<String, String> copyFilters(Map<String, String> filters) {
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        if (filters != null) {
            for (Map.Entry<String, String> value : filters.entrySet()) {
                if (value.getKey() == null || value.getValue() == null) {
                    throw new IllegalArgumentException("metadata filter is invalid");
                }
                copied.put(value.getKey(), value.getValue());
            }
        }
        return Collections.unmodifiableMap(copied);
    }

    public float[] getVector() {
        return vector.clone();
    }

    /**
     * 语义记忆查询构建器；必填检索参数在工厂方法中传入，过滤条件与查询文本按需设置。
     */
    public static final class Builder {

        private final float[] vector;
        private final int topK;
        private final double minScore;
        private final long nowEpochMillis;
        private MemorySourceTypeEnum sourceType;
        private String conversationId;
        private Map<String, String> filters;
        private String queryText;

        private Builder(float[] vector, int topK, double minScore, long nowEpochMillis) {
            this.vector = vector;
            this.topK = topK;
            this.minScore = minScore;
            this.nowEpochMillis = nowEpochMillis;
        }

        public Builder sourceType(MemorySourceTypeEnum sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder filters(Map<String, String> filters) {
            this.filters = filters;
            return this;
        }

        public Builder queryText(String queryText) {
            this.queryText = queryText;
            return this;
        }

        public SemanticMemoryQuery build() {
            return new SemanticMemoryQuery(this);
        }
    }
}
