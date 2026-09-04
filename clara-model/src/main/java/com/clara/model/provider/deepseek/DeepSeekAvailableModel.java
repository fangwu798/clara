package com.clara.model.provider.deepseek;

public final class DeepSeekAvailableModel implements Comparable<DeepSeekAvailableModel> {
    private final String modelId;
    private final String ownedBy;

    DeepSeekAvailableModel(String modelId, String ownedBy) {
        this.modelId = modelId;
        this.ownedBy = ownedBy;
    }

    public String getModelId() { return modelId; }
    public String getOwnedBy() { return ownedBy; }

    public int compareTo(DeepSeekAvailableModel other) {
        return modelId.compareTo(other.modelId);
    }
}
