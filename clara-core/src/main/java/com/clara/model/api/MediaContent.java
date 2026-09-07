package com.clara.model.api;

import java.util.Objects;

/**
 * 表示媒体携带的内容。
 */
public final class MediaContent implements Content {
    private final String mediaType;
    private final String assetId;
    public MediaContent(String mediaType, String assetId) {
        if (mediaType == null || mediaType.trim().isEmpty()) {
            throw new IllegalArgumentException("mediaType must not be blank");
        }
        if (assetId == null || assetId.trim().isEmpty()) {
            throw new IllegalArgumentException("assetId must not be blank");
        }
        this.mediaType = mediaType;
        this.assetId = assetId;
    }
    public String getType() { return mediaType; }
    public String getAssetId() { return assetId; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MediaContent)) return false;
        MediaContent that = (MediaContent) other;
        return mediaType.equals(that.mediaType) && assetId.equals(that.assetId);
    }
    @Override public int hashCode() { return Objects.hash(mediaType, assetId); }
}
