package com.clara.model.api.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AudioTranscriptionResult {
    private final String text;
    private final String detectedLanguage;
    private final Long durationMs;
    private final List<TranscriptionSegment> segments;
    private final Map<String, String> providerMetadata;

    public AudioTranscriptionResult(String text, String detectedLanguage, Long durationMs,
            List<TranscriptionSegment> segments, Map<String, String> providerMetadata) {
        this.text = MediaRequestValues.required(text, "text");
        if (durationMs != null && durationMs.longValue() < 0) throw new IllegalArgumentException("durationMs must not be negative");
        List<TranscriptionSegment> safe = segments == null ? Collections.<TranscriptionSegment>emptyList() : segments;
        if (safe.contains(null)) throw new IllegalArgumentException("segments must not contain null");
        long previousEnd = 0;
        for (TranscriptionSegment segment : safe) {
            if (segment.getStartMs() < previousEnd) throw new IllegalArgumentException("segments must be ordered and non-overlapping");
            if (durationMs != null && segment.getEndMs() > durationMs.longValue())
                throw new IllegalArgumentException("segment exceeds duration");
            previousEnd = segment.getEndMs();
        }
        this.detectedLanguage = detectedLanguage;
        this.durationMs = durationMs;
        this.segments = Collections.unmodifiableList(new ArrayList<TranscriptionSegment>(safe));
        this.providerMetadata = providerMetadata == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(providerMetadata));
    }
    public String getText() { return text; }
    public String getDetectedLanguage() { return detectedLanguage; }
    public Long getDurationMs() { return durationMs; }
    public List<TranscriptionSegment> getSegments() { return segments; }
    public Map<String, String> getProviderMetadata() { return providerMetadata; }
}
