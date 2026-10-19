package com.clara.dto.memory;

import lombok.Data;

/**
 * 记忆匹配结果：一条命中条目及其相似度得分。
 */
@Data
public final class MemoryMatch {

    private final MemoryEntry entry;
    private final double score;

    /**
     * 创建匹配结果。
     *
     * @param entry 命中条目，不可为 null
     * @param score 相似度得分，须在 {@code [-1, 1]} 区间
     * @throws IllegalArgumentException entry 为 null 或 score 越界 / 为 NaN 时
     */
    public MemoryMatch(MemoryEntry entry, double score) {
        if (entry == null || Double.isNaN(score) || score < -1.0 || score > 1.0) {
            throw new IllegalArgumentException("memory match is invalid");
        }
        this.entry = entry;
        this.score = score;
    }

}
