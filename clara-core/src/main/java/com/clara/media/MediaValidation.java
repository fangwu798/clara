package com.clara.media;

public final class MediaValidation {
    private final String detectedMimeType;
    private final Integer width;
    private final Integer height;
    private final Long durationMs;

    public MediaValidation(String detectedMimeType, Integer width, Integer height, Long durationMs) {
        if (detectedMimeType == null || detectedMimeType.trim().isEmpty())
            throw new IllegalArgumentException("detectedMimeType must not be blank");
        if ((width == null) != (height == null)) throw new IllegalArgumentException("width and height must be set together");
        if (width != null && (width.intValue() <= 0 || height.intValue() <= 0))
            throw new IllegalArgumentException("dimensions must be positive");
        if (durationMs != null && durationMs.longValue() < 0)
            throw new IllegalArgumentException("durationMs must not be negative");
        this.detectedMimeType = detectedMimeType;
        this.width = width;
        this.height = height;
        this.durationMs = durationMs;
    }
    public String getDetectedMimeType() { return detectedMimeType; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public Long getDurationMs() { return durationMs; }
}
