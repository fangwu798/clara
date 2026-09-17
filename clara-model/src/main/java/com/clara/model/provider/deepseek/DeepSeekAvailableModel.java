package com.clara.model.provider.deepseek;

import lombok.Data;

/**
 * 提供DeepSeek可用模型能力。
 */
@Data
public final class DeepSeekAvailableModel implements Comparable<DeepSeekAvailableModel> {
    private final String modelId;
    private final String ownedBy;

    DeepSeekAvailableModel(String modelId, String ownedBy) {
        this.modelId = modelId;
        this.ownedBy = ownedBy;
    }
    

    public int compareTo(DeepSeekAvailableModel other) {
        return modelId.compareTo(other.modelId);
    }
}
