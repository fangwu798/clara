package com.clara.media;

/**
 * 描述媒体的元数据。
 */
public final class MediaMetadata {
    private final Integer width;
    private final Integer height;
    private final Long durationMs;

    public MediaMetadata(Integer width, Integer height, Long durationMs) {
        if ((width == null) != (height == null)) { throw new IllegalArgumentException("width and height must be set together"); }
        if (width != null && (width.intValue() <= 0 || height.intValue() <= 0)) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        if (durationMs != null && durationMs.longValue() < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        this.width = width;
        this.height = height;
        this.durationMs = durationMs;
    }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public Long getDurationMs() { return durationMs; }
}
