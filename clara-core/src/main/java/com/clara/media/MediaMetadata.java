package com.clara.media;

import lombok.Data;

/**
 * 描述媒体的元数据。
 */
@Data
public final class MediaMetadata {
    private final Integer width;
    private final Integer height;
    private final Long durationMs;

    public MediaMetadata(Integer width, Integer height, Long durationMs) {
        if ((width == null) != (height == null)) {
            throw new IllegalArgumentException("width and height must be set together");
        }
        if (width != null && (width <= 0 || height <= 0)) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        this.width = width;
        this.height = height;
        this.durationMs = durationMs;
    }
}
