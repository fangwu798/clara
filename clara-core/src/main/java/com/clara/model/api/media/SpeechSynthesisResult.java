package com.clara.model.api.media;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 封装语音合成操作的执行结果。
 */
public final class SpeechSynthesisResult {
    private final String audioAssetId;
    private final Long durationMs;
    private final Map<String, String> providerMetadata;

    public SpeechSynthesisResult(String audioAssetId, Long durationMs, Map<String, String> providerMetadata) {
        this.audioAssetId = MediaRequestValues.required(audioAssetId, "audioAssetId");
        if (durationMs != null && durationMs.longValue() < 0) { throw new IllegalArgumentException("durationMs must not be negative"); }
        this.durationMs = durationMs;
        this.providerMetadata = providerMetadata == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(providerMetadata));
    }
    public String getAudioAssetId() { return audioAssetId; }
    public Long getDurationMs() { return durationMs; }
    public Map<String, String> getProviderMetadata() { return providerMetadata; }
}
