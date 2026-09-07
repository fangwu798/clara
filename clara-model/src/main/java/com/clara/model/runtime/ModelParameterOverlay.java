package com.clara.model.runtime;

import com.clara.model.api.ModelParameters;
import java.util.Collections;
import java.util.List;

/**
 * 合并并覆盖模型参数。
 */
public final class ModelParameterOverlay {
    public ModelParameters merge(ModelParameters lower, ModelParameters higher) {
        if (lower == null) { throw new IllegalArgumentException("lower parameters must not be null"); }
        if (higher == null) { throw new IllegalArgumentException("higher parameters must not be null"); }
        String model = lower.getModel();
        Double temperature = lower.getTemperature();
        Double topP = lower.getTopP();
        Integer topK = lower.getTopK();
        Integer maxTokens = lower.getMaxTokens();
        Double frequencyPenalty = lower.getFrequencyPenalty();
        Double presencePenalty = lower.getPresencePenalty();
        String responseFormat = lower.getResponseFormat();
        List<String> stops = lower.getStops();
        if (higher.getModel() != null) model = higher.getModel();
        if (higher.getTemperature() != null) temperature = higher.getTemperature();
        if (higher.getTopP() != null) topP = higher.getTopP();
        if (higher.getTopK() != null) topK = higher.getTopK();
        if (higher.getMaxTokens() != null) maxTokens = higher.getMaxTokens();
        if (higher.getFrequencyPenalty() != null) frequencyPenalty = higher.getFrequencyPenalty();
        if (higher.getPresencePenalty() != null) presencePenalty = higher.getPresencePenalty();
        if (higher.getResponseFormat() != null) responseFormat = higher.getResponseFormat();
        if (higher.isClearStops()) {
            stops = Collections.emptyList();
        } else if (!higher.getStops().isEmpty()) {
            stops = higher.getStops();
        }
        return new ModelParameters(model, temperature, topP, topK, maxTokens,
                frequencyPenalty, presencePenalty, responseFormat, stops,
                higher.isClearStops());
    }
}
