package com.clara.model.provider.compatible;

import com.clara.model.api.ModelProvider;
import java.net.URI;

public final class OpenAiCompatibleEmbeddingConfig {
    private final ModelProvider provider;
    private final URI endpoint;
    private final String modelId;
    private final String apiProfile;
    private final String secretRef;
    private final int dimension;
    private final int maxInputs;
    private final int maxTextChars;
    private final int maxTotalChars;

    public OpenAiCompatibleEmbeddingConfig(ModelProvider provider, URI endpoint,
            String modelId, String apiProfile, String secretRef, int dimension,
            int maxInputs, int maxTextChars, int maxTotalChars) {
        if (provider != ModelProvider.OPENAI && provider != ModelProvider.ALIBABA)
            throw new IllegalArgumentException("provider is not confirmed for compatible embeddings");
        if (endpoint == null) throw new IllegalArgumentException("endpoint must not be null");
        this.modelId = required(modelId, "modelId");
        this.apiProfile = required(apiProfile, "apiProfile");
        this.secretRef = required(secretRef, "secretRef");
        if (dimension <= 0 || maxInputs <= 0 || maxTextChars <= 0 || maxTotalChars <= 0
                || maxTotalChars < maxTextChars)
            throw new IllegalArgumentException("embedding limits must be positive and consistent");
        this.provider = provider;
        this.endpoint = endpoint;
        this.dimension = dimension;
        this.maxInputs = maxInputs;
        this.maxTextChars = maxTextChars;
        this.maxTotalChars = maxTotalChars;
    }

    public ModelProvider getProvider() { return provider; }
    public URI getEndpoint() { return endpoint; }
    public String getModelId() { return modelId; }
    public String getApiProfile() { return apiProfile; }
    public String getSecretRef() { return secretRef; }
    public int getDimension() { return dimension; }
    public int getMaxInputs() { return maxInputs; }
    public int getMaxTextChars() { return maxTextChars; }
    public int getMaxTotalChars() { return maxTotalChars; }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
