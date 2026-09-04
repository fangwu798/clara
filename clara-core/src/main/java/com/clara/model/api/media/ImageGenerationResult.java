package com.clara.model.api.media;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ImageGenerationResult {
    private final List<String> assetIds;
    private final String requestId;
    private final Map<String, String> providerMetadata;

    public ImageGenerationResult(List<String> assetIds, String requestId, Map<String, String> providerMetadata) {
        this.assetIds = MediaRequestValues.assetIds(assetIds, "assetIds");
        if (this.assetIds.isEmpty()) throw new IllegalArgumentException("assetIds must not be empty");
        this.requestId = requestId;
        this.providerMetadata = providerMetadata == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(providerMetadata));
    }
    public List<String> getAssetIds() { return assetIds; }
    public String getRequestId() { return requestId; }
    public Map<String, String> getProviderMetadata() { return providerMetadata; }
}
