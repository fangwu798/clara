package com.clara.model.api.media;

import com.clara.kernel.InvocationContext;
import com.clara.model.api.ProviderOptions;

/**
 * 封装音频转写操作的输入参数。
 */
public final class AudioTranscriptionRequest {
    private final String audioAssetId;
    private final String prompt;
    private final String language;
    private final Double temperature;
    private final String responseFormat;
    private final ProviderOptions providerOptions;
    private final InvocationContext context;

    public AudioTranscriptionRequest(String audioAssetId, String prompt, String language,
            Double temperature, String responseFormat, ProviderOptions providerOptions,
            InvocationContext context) {
        this.audioAssetId = MediaRequestValues.required(audioAssetId, "audioAssetId");
        if (temperature != null && (temperature.doubleValue() < 0 || temperature.doubleValue() > 1
                || temperature.isNaN() || temperature.isInfinite())) {
            throw new IllegalArgumentException("temperature must be between 0 and 1");
        }
        if (context == null) { throw new IllegalArgumentException("context must not be null"); }
        this.prompt = prompt;
        this.language = language;
        this.temperature = temperature;
        this.responseFormat = responseFormat;
        this.providerOptions = providerOptions;
        this.context = context;
    }
    public String getAudioAssetId() { return audioAssetId; }
    public String getPrompt() { return prompt; }
    public String getLanguage() { return language; }
    public Double getTemperature() { return temperature; }
    public String getResponseFormat() { return responseFormat; }
    public ProviderOptions getProviderOptions() { return providerOptions; }
    public InvocationContext getContext() { return context; }
}
