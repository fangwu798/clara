package com.clara.model.runtime;

import com.clara.model.api.ModelCapability;
import com.clara.model.api.ModelProvider;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 描述服务提供商模型。
 */
public final class ProviderModelDescriptor {
    private final ModelProvider provider;
    private final String modelId;
    private final String alias;
    private final String apiProfile;
    private final String region;
    private final String revision;
    private final Set<ModelCapability> capabilities;
    private final ProviderModelMetadata metadata;

    public ProviderModelDescriptor(ModelProvider provider, String modelId, String alias,
            String apiProfile, String region, String revision,
            Set<ModelCapability> capabilities) {
        this(provider, modelId, alias, apiProfile, region, revision, capabilities,
                ProviderModelMetadata.empty());
    }

    public ProviderModelDescriptor(ModelProvider provider, String modelId, String alias,
            String apiProfile, String region, String revision,
            Set<ModelCapability> capabilities, ProviderModelMetadata metadata) {
        if (provider == null) { throw new IllegalArgumentException("provider must not be null"); }
        if (isBlank(modelId)) { throw new IllegalArgumentException("modelId must not be blank"); }
        if (isBlank(alias)) { throw new IllegalArgumentException("alias must not be blank"); }
        if (capabilities == null) { throw new IllegalArgumentException("capabilities must not be null"); }
        if (metadata == null) { throw new IllegalArgumentException("metadata must not be null"); }
        this.provider = provider;
        this.modelId = modelId;
        this.alias = alias;
        this.apiProfile = apiProfile;
        this.region = region;
        this.revision = revision;
        this.capabilities = capabilities.isEmpty()
                ? Collections.<ModelCapability>emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
        this.metadata = metadata;
    }
    public ModelProvider getProvider() { return provider; }
    public String getModelId() { return modelId; }
    public String getAlias() { return alias; }
    public String getApiProfile() { return apiProfile; }
    public String getRegion() { return region; }
    public String getRevision() { return revision; }
    public Set<ModelCapability> getCapabilities() { return capabilities; }
    public ProviderModelMetadata getMetadata() { return metadata; }
    public boolean supports(ModelCapability capability) {
        return capabilities.contains(capability);
    }
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
