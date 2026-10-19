package com.clara.dto.memory;

import com.clara.enums.memory.MemorySourceTypeEnum;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记忆条目：向量库中的一条记录，含正文、向量、会话关联与元数据。
 *
 * <p>无租户：不含 {@code tenantId}，语义记忆仅按会话标识关联。{@link #conversationId()} 仅在来源为
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

    private final String id;
    private final MemorySourceTypeEnum sourceType;
    private final String sourceId;
    private final String conversationId;
    private final String content;
    private final String contentHash;
    private final float[] vector;
    private final long createdAtEpochMillis;
    private final long expiresAtEpochMillis;
    private final Map<String, String> metadata;

    /**
     * 创建记忆条目。
     *
     * @param id                   条目标识，不可为空白
     * @param sourceType           来源类型，不可为 null
     * @param sourceId             来源标识（如技能 ID、提示词 ID、会话 ID），不可为空白
     * @param conversationId       会话关联，仅 {@code CONVERSATION} 来源必填，其余可为 null
     * @param content              正文，不可为 null
     * @param contentHash          正文 SHA-256 哈希（64 位十六进制），不可为 null
     * @param vector               向量，不可为 null、空、含 NaN/Inf 或零范数
     * @param createdAtEpochMillis 创建时间（Unix epoch 毫秒），不可为负
     * @param expiresAtEpochMillis 过期时间（Unix epoch 毫秒），0 表示不过期；大于 0 时必须晚于创建时间
     * @param metadata             元数据，可为 null
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public MemoryEntry(String id, MemorySourceTypeEnum sourceType, String sourceId, String conversationId,
            String content, String contentHash, float[] vector, long createdAtEpochMillis,
            long expiresAtEpochMillis, Map<String, String> metadata) {
        if (blank(id) || sourceType == null || blank(sourceId) || content == null || contentHash == null
                || !contentHash.matches("[0-9a-f]{64}") || vector == null || vector.length == 0
                || createdAtEpochMillis < 0 || expiresAtEpochMillis < 0
                || (expiresAtEpochMillis > 0 && expiresAtEpochMillis <= createdAtEpochMillis)) {
            throw new IllegalArgumentException("memory entry is invalid");
        }
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
        this.id = id;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.conversationId = conversationId;
        this.content = content;
        this.contentHash = contentHash;
        this.vector = vector.clone();
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.expiresAtEpochMillis = expiresAtEpochMillis;
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        if (metadata != null) {
            for (Map.Entry<String, String> value : metadata.entrySet()) {
                if (blank(value.getKey()) || value.getValue() == null) {
                    throw new IllegalArgumentException("metadata is invalid");
                }
                copied.put(value.getKey(), value.getValue());
            }
        }
        this.metadata = Collections.unmodifiableMap(copied);
    }

    public String getId() {
        return id;
    }

    public MemorySourceTypeEnum getSourceType() {
        return sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getContent() {
        return content;
    }

    public String getContentHash() {
        return contentHash;
    }

    public float[] getVector() {
        return vector.clone();
    }

    public int getDimension() {
        return vector.length;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public long getExpiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public boolean isExpired(long now) {
        return expiresAtEpochMillis > 0 && now >= expiresAtEpochMillis;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
