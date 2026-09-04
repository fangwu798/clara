package com.clara.model.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModelParameters {
    private final String model;
    private final Double temperature;
    private final Double topP;
    private final Integer topK;
    private final Integer maxTokens;
    private final Double frequencyPenalty;
    private final Double presencePenalty;
    private final String responseFormat;
    private final List<String> stops;
    private final boolean clearStops;

    public ModelParameters(String model, Double temperature, Double topP,
            Integer maxTokens, List<String> stops, boolean clearStops) {
        this(model, temperature, topP, null, maxTokens, null, null, null, stops, clearStops);
    }

    public ModelParameters(String model, Double temperature, Double topP, Integer topK,
            Integer maxTokens, Double frequencyPenalty, Double presencePenalty,
            String responseFormat, List<String> stops, boolean clearStops) {
        if (temperature != null && temperature.doubleValue() < 0.0d) {
            throw new IllegalArgumentException("temperature must not be negative");
        }
        if (topP != null && (topP.doubleValue() < 0.0d || topP.doubleValue() > 1.0d)) {
            throw new IllegalArgumentException("topP must be between 0 and 1");
        }
        if (topK != null && topK.intValue() <= 0) throw new IllegalArgumentException("topK must be positive");
        if (maxTokens != null && maxTokens.intValue() <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        List<String> safeStops = stops == null ? Collections.<String>emptyList() : stops;
        if (safeStops.contains(null)) throw new IllegalArgumentException("stops must not contain null");
        this.model = model;
        this.temperature = temperature;
        this.topP = topP;
        this.topK = topK;
        this.maxTokens = maxTokens;
        this.frequencyPenalty = frequencyPenalty;
        this.presencePenalty = presencePenalty;
        this.responseFormat = responseFormat;
        this.stops = Collections.unmodifiableList(new ArrayList<String>(safeStops));
        this.clearStops = clearStops;
    }
    public String getModel() { return model; }
    public Double getTemperature() { return temperature; }
    public Double getTopP() { return topP; }
    public Integer getTopK() { return topK; }
    public Integer getMaxTokens() { return maxTokens; }
    public Double getFrequencyPenalty() { return frequencyPenalty; }
    public Double getPresencePenalty() { return presencePenalty; }
    public String getResponseFormat() { return responseFormat; }
    public List<String> getStops() { return stops; }
    public boolean isClearStops() { return clearStops; }
}
