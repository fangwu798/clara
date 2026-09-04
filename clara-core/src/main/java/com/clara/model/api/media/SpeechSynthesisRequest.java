package com.clara.model.api.media;

import com.clara.kernel.InvocationContext;
import com.clara.model.api.ProviderOptions;

public final class SpeechSynthesisRequest {
    private final String text;
    private final String voice;
    private final String language;
    private final String outputMimeType;
    private final Integer sampleRateHz;
    private final Double speed;
    private final ProviderOptions providerOptions;
    private final InvocationContext context;

    public SpeechSynthesisRequest(String text, String voice, String language, String outputMimeType,
            Integer sampleRateHz, Double speed, ProviderOptions providerOptions, InvocationContext context) {
        this.text = MediaRequestValues.required(text, "text");
        this.outputMimeType = MediaRequestValues.required(outputMimeType, "outputMimeType");
        if (sampleRateHz != null && sampleRateHz.intValue() <= 0)
            throw new IllegalArgumentException("sampleRateHz must be positive");
        if (speed != null && (speed.doubleValue() <= 0 || speed.isNaN() || speed.isInfinite()))
            throw new IllegalArgumentException("speed must be finite and positive");
        if (context == null) throw new IllegalArgumentException("context must not be null");
        this.voice = voice;
        this.language = language;
        this.sampleRateHz = sampleRateHz;
        this.speed = speed;
        this.providerOptions = providerOptions;
        this.context = context;
    }
    public String getText() { return text; }
    public String getVoice() { return voice; }
    public String getLanguage() { return language; }
    public String getOutputMimeType() { return outputMimeType; }
    public Integer getSampleRateHz() { return sampleRateHz; }
    public Double getSpeed() { return speed; }
    public ProviderOptions getProviderOptions() { return providerOptions; }
    public InvocationContext getContext() { return context; }
}
