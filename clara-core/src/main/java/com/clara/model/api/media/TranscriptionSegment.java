package com.clara.model.api.media;

public final class TranscriptionSegment {
    private final long startMs;
    private final long endMs;
    private final String text;

    public TranscriptionSegment(long startMs, long endMs, String text) {
        if (startMs < 0 || endMs < startMs) throw new IllegalArgumentException("invalid segment range");
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = MediaRequestValues.required(text, "text");
    }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public String getText() { return text; }
}
