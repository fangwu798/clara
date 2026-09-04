package com.clara.model.runtime;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ProviderModelMetadata {
    private static final ProviderModelMetadata EMPTY = new Builder().build();

    private final Set<ModelModality> inputModalities;
    private final Set<ModelModality> outputModalities;
    private final Integer contextWindowTokens;
    private final Integer maxOutputTokens;
    private final Set<String> supportedParameters;
    private final Set<ModelExecutionMode> executionModes;
    private final Set<String> dataResidencyLabels;
    private final String pricingVersion;
    private final Long releasedAt;
    private final Long deprecatedAt;
    private final Long discoveredAt;
    private final String sourceRevision;
    private final ModelMetadataSource source;

    private ProviderModelMetadata(Builder builder) {
        if (builder.contextWindowTokens != null && builder.contextWindowTokens.intValue() <= 0) {
            throw new IllegalArgumentException("contextWindowTokens must be positive");
        }
        if (builder.maxOutputTokens != null && builder.maxOutputTokens.intValue() <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        if (builder.releasedAt != null && builder.deprecatedAt != null
                && builder.deprecatedAt.longValue() < builder.releasedAt.longValue()) {
            throw new IllegalArgumentException("deprecatedAt must not be before releasedAt");
        }
        this.inputModalities = immutableEnumSet(builder.inputModalities);
        this.outputModalities = immutableEnumSet(builder.outputModalities);
        this.contextWindowTokens = builder.contextWindowTokens;
        this.maxOutputTokens = builder.maxOutputTokens;
        this.supportedParameters = immutableStrings(builder.supportedParameters, "supportedParameters");
        this.executionModes = immutableEnumSet(builder.executionModes);
        this.dataResidencyLabels = immutableStrings(builder.dataResidencyLabels, "dataResidencyLabels");
        this.pricingVersion = builder.pricingVersion;
        this.releasedAt = builder.releasedAt;
        this.deprecatedAt = builder.deprecatedAt;
        this.discoveredAt = builder.discoveredAt;
        this.sourceRevision = builder.sourceRevision;
        this.source = builder.source;
    }

    public static ProviderModelMetadata empty() { return EMPTY; }
    public static Builder builder() { return new Builder(); }

    public Set<ModelModality> getInputModalities() { return inputModalities; }
    public Set<ModelModality> getOutputModalities() { return outputModalities; }
    public Integer getContextWindowTokens() { return contextWindowTokens; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public Set<String> getSupportedParameters() { return supportedParameters; }
    public Set<ModelExecutionMode> getExecutionModes() { return executionModes; }
    public Set<String> getDataResidencyLabels() { return dataResidencyLabels; }
    public String getPricingVersion() { return pricingVersion; }
    public Long getReleasedAt() { return releasedAt; }
    public Long getDeprecatedAt() { return deprecatedAt; }
    public Long getDiscoveredAt() { return discoveredAt; }
    public String getSourceRevision() { return sourceRevision; }
    public ModelMetadataSource getSource() { return source; }

    private static <E extends Enum<E>> Set<E> immutableEnumSet(Set<E> values) {
        if (values == null || values.isEmpty()) return Collections.emptySet();
        return Collections.unmodifiableSet(EnumSet.copyOf(values));
    }

    private static Set<String> immutableStrings(Set<String> values, String name) {
        if (values == null || values.isEmpty()) return Collections.emptySet();
        Set<String> copy = new LinkedHashSet<String>();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(name + " must not contain blank values");
            }
            copy.add(value);
        }
        return Collections.unmodifiableSet(copy);
    }

    public static final class Builder {
        private Set<ModelModality> inputModalities;
        private Set<ModelModality> outputModalities;
        private Integer contextWindowTokens;
        private Integer maxOutputTokens;
        private Set<String> supportedParameters;
        private Set<ModelExecutionMode> executionModes;
        private Set<String> dataResidencyLabels;
        private String pricingVersion;
        private Long releasedAt;
        private Long deprecatedAt;
        private Long discoveredAt;
        private String sourceRevision;
        private ModelMetadataSource source;

        public Builder inputModalities(Set<ModelModality> value) { inputModalities = value; return this; }
        public Builder outputModalities(Set<ModelModality> value) { outputModalities = value; return this; }
        public Builder contextWindowTokens(Integer value) { contextWindowTokens = value; return this; }
        public Builder maxOutputTokens(Integer value) { maxOutputTokens = value; return this; }
        public Builder supportedParameters(Set<String> value) { supportedParameters = value; return this; }
        public Builder executionModes(Set<ModelExecutionMode> value) { executionModes = value; return this; }
        public Builder dataResidencyLabels(Set<String> value) { dataResidencyLabels = value; return this; }
        public Builder pricingVersion(String value) { pricingVersion = value; return this; }
        public Builder releasedAt(Long value) { releasedAt = value; return this; }
        public Builder deprecatedAt(Long value) { deprecatedAt = value; return this; }
        public Builder discoveredAt(Long value) { discoveredAt = value; return this; }
        public Builder sourceRevision(String value) { sourceRevision = value; return this; }
        public Builder source(ModelMetadataSource value) { source = value; return this; }
        public ProviderModelMetadata build() { return new ProviderModelMetadata(this); }
    }
}
