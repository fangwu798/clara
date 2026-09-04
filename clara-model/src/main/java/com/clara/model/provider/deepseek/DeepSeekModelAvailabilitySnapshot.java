package com.clara.model.provider.deepseek;

import com.clara.model.api.ModelProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DeepSeekModelAvailabilitySnapshot {
    private final String apiProfile;
    private final long discoveredAtEpochMillis;
    private final List<DeepSeekAvailableModel> models;

    DeepSeekModelAvailabilitySnapshot(String apiProfile, long discoveredAtEpochMillis,
            List<DeepSeekAvailableModel> models) {
        this.apiProfile = apiProfile;
        this.discoveredAtEpochMillis = discoveredAtEpochMillis;
        this.models = Collections.unmodifiableList(new ArrayList<DeepSeekAvailableModel>(models));
    }

    public ModelProvider getProvider() { return ModelProvider.DEEPSEEK; }
    public String getApiProfile() { return apiProfile; }
    public long getDiscoveredAtEpochMillis() { return discoveredAtEpochMillis; }
    public List<DeepSeekAvailableModel> getModels() { return models; }

    public boolean contains(String modelId) {
        if (modelId == null) return false;
        for (DeepSeekAvailableModel model : models)
            if (modelId.equals(model.getModelId())) return true;
        return false;
    }
}
