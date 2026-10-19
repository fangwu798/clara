package com.clara.dto.memory;

import com.clara.utils.StringUtils;
import com.clara.enums.memory.MemorySourceTypeEnum;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记忆条目：向量库中的一条记录，含正文、向量、会话关联与元数据。
 *
 * <p>无租户：不含 {@code tenantId}，语义记忆仅按会话标识关联。
 * {@link MemorySourceTypeEnum#CONVERSATION} 时必填，其余来源可为 null。
 */
@Data
public final class MemoryEntry {

    /**
     * 语义记忆元数据键：工具方法的入参 JSON Schema（仅 {@code TOOL_DESCRIPTION} 来源条目携带）。
     *
     * <p>技能注册表在索引工具描述时把工具方法的 {@code parameters} 写入该键，供编排层在匹配到工具后
     * 重建 {@code ToolSpecification.parameters} 下发给模型，使模型能看到工具的真实入参。
     */
    public static final String TOOL_PARAMETERS_METADATA_KEY = "parameters";

    /**
     * 条目标识，不可为空白。
     */
    private final String id;
    /**
     * 记忆来源类型，不可为 null。
     */
    private final MemorySourceTypeEnum sourceType;
    /**
     * 来源标识（如技能 ID、提示词 ID、会话 ID），不可为空白。
     */
    private final String sourceId;
    /**
     * 会话关联，仅 {@code CONVERSATION} 来源必填，其余可为 null。
     */
    private final String conversationId;
    /**
     * 正文，不可为 null。
     */
    private final String content;
    /**
     * 正文 SHA-256 哈希，固定 64 位小写十六进制。
     */
    private final String contentHash;
    /**
     * 向量，构造时校验并做防御性复制。
     */
    private final float[] vector;
    /**
     * 创建时间（Unix epoch 毫秒），不可为负。
     */
    private final long createdAtEpochMillis;
    /**
     * 过期时间（Unix epoch 毫秒），0 表示不过期。
     */
    private final long expiresAtEpochMillis;
    /**
     * 只读元数据副本，键不可为空白、值不可为 null。
     */
    private final Map<String, String> metadata;

    /**
     * 由构建器创建记忆条目并完成校验与防御性复制。
     *
     * @param builder 已填充必填字段的构建器，不能为 null
     * @throws IllegalArgumentException 任意字段不合法时
     */
    private MemoryEntry(Builder builder) {
        if (StringUtils.isBlank(builder.id) || builder.sourceType == null || StringUtils.isBlank(builder.sourceId)
                || builder.content == null || builder.contentHash == null
                || !builder.contentHash.matches("[0-9a-f]{64}")
                || builder.vector == null || builder.vector.length == 0
                || builder.createdAtEpochMillis < 0 || builder.expiresAtEpochMillis < 0
                || builder.expiresAtEpochMillis > 0
                && builder.expiresAtEpochMillis <= builder.createdAtEpochMillis) {
            throw new IllegalArgumentException("memory entry is invalid");
        }
        validateVector(builder.vector);
        this.id = builder.id;
        this.sourceType = builder.sourceType;
        this.sourceId = builder.sourceId;
        this.conversationId = builder.conversationId;
        this.content = builder.content;
        this.contentHash = builder.contentHash;
        this.vector = builder.vector.clone();
        this.createdAtEpochMillis = builder.createdAtEpochMillis;
        this.expiresAtEpochMillis = builder.expiresAtEpochMillis;
        this.metadata = copyMetadata(builder.metadata);
    }

    /**
     * 创建记忆条目构建器。
     *
     * @param id          条目标识，不可为空白
     * @param sourceType  来源类型，不可为 null
     * @param sourceId    来源标识，不可为空白
     * @param content     正文，不可为 null
     * @param contentHash 正文 SHA-256 哈希，不可为 null 且必须是 64 位十六进制
     * @param vector      向量，不可为 null、空、含 NaN/Inf 或零范数
     * @return 构建器，非 null
     */
    public static Builder builder(String id, MemorySourceTypeEnum sourceType, String sourceId, String content,
                                  String contentHash, float[] vector) {
        return new Builder(id, sourceType, sourceId, content, contentHash, vector);
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
                throw new IllegalArgumentException("vector is invalid");
            }
            norm += (double) value * value;
        }
        if (norm == 0) {
            throw new IllegalArgumentException("vector must not be zero");
        }
    }

    /**
     * 复制元数据为只读映射。
     *
     * @param metadata 原始元数据，可为 null
     * @return 只读元数据副本，非 null
     * @throws IllegalArgumentException 键为空白或值为 null 时
     */
    private static Map<String, String> copyMetadata(Map<String, String> metadata) {
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        if (metadata != null) {
            for (Map.Entry<String, String> value : metadata.entrySet()) {
                if (StringUtils.isBlank(value.getKey()) || value.getValue() == null) {
                    throw new IllegalArgumentException("metadata is invalid");
                }
                copied.put(value.getKey(), value.getValue());
            }
        }
        return Collections.unmodifiableMap(copied);
    }

    public float[] getVector() {
        return vector.clone();
    }

    public int getDimension() {
        return vector.length;
    }

    public boolean isExpired(long now) {
        return expiresAtEpochMillis > 0 && now >= expiresAtEpochMillis;
    }

    /**
     * 记忆条目构建器；必填字段在工厂方法中传入，其余字段按需设置。
     */
    public static final class Builder {

        private final String id;
        private final MemorySourceTypeEnum sourceType;
        private final String sourceId;
        private final String content;
        private final String contentHash;
        private final float[] vector;
        private String conversationId;
        private long createdAtEpochMillis;
        private long expiresAtEpochMillis;
        private Map<String, String> metadata;

        private Builder(String id, MemorySourceTypeEnum sourceType, String sourceId, String content,
                        String contentHash, float[] vector) {
            this.id = id;
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.content = content;
            this.contentHash = contentHash;
            this.vector = vector;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder createdAtEpochMillis(long createdAtEpochMillis) {
            this.createdAtEpochMillis = createdAtEpochMillis;
            return this;
        }

        public Builder expiresAtEpochMillis(long expiresAtEpochMillis) {
            this.expiresAtEpochMillis = expiresAtEpochMillis;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public MemoryEntry build() {
            return new MemoryEntry(this);
        }
    }
}
