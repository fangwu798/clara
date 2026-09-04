package com.clara.model.provider.deepseek;

import java.net.URI;

public final class DeepSeekModelCatalogConfig {
    private final URI endpoint;
    private final String apiProfile;
    private final String secretRef;
    private final int maxModels;
    private final int maxModelIdChars;

    public DeepSeekModelCatalogConfig(URI endpoint, String apiProfile, String secretRef,
            int maxModels, int maxModelIdChars) {
        if (endpoint == null) throw new IllegalArgumentException("endpoint must not be null");
        if (maxModels <= 0 || maxModelIdChars <= 0)
            throw new IllegalArgumentException("catalog limits must be positive");
        this.endpoint = endpoint;
        this.apiProfile = required(apiProfile, "apiProfile");
        this.secretRef = required(secretRef, "secretRef");
        this.maxModels = maxModels;
        this.maxModelIdChars = maxModelIdChars;
    }

    public URI getEndpoint() { return endpoint; }
    public String getApiProfile() { return apiProfile; }
    public String getSecretRef() { return secretRef; }
    public int getMaxModels() { return maxModels; }
    public int getMaxModelIdChars() { return maxModelIdChars; }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
