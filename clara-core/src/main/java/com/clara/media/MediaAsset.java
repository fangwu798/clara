package com.clara.media;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MediaAsset {
    private final String assetId;
    private final MediaType mediaType;
    private final String mimeType;
    private final long size;
    private final String sha256;
    private final String storageUri;
    private final String sourceUri;
    private final Integer width;
    private final Integer height;
    private final Long durationMs;
    private final String ownerTenantId;
    private final long createdAt;
    private final Long expiresAt;
    private final Map<String, String> metadata;

    public MediaAsset(String assetId, MediaType mediaType, String mimeType, long size, String sha256,
            String storageUri, String sourceUri, Integer width, Integer height, Long durationMs,
            String ownerTenantId, long createdAt, Long expiresAt, Map<String, String> metadata) {
        this.assetId = required(assetId, "assetId");
        if (mediaType == null) throw new IllegalArgumentException("mediaType must not be null");
        this.mediaType = mediaType;
        this.mimeType = required(mimeType, "mimeType");
        if (size < 0) throw new IllegalArgumentException("size must not be negative");
        this.size = size;
        this.sha256 = required(sha256, "sha256");
        this.storageUri = required(storageUri, "storageUri");
        this.sourceUri = sourceUri;
        this.width = width;
        this.height = height;
        this.durationMs = durationMs;
        this.ownerTenantId = required(ownerTenantId, "ownerTenantId");
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.metadata = metadata == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(metadata));
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
    public String getAssetId() { return assetId; }
    public MediaType getMediaType() { return mediaType; }
    public String getMimeType() { return mimeType; }
    public long getSize() { return size; }
    public String getSha256() { return sha256; }
    public String getStorageUri() { return storageUri; }
    public String getSourceUri() { return sourceUri; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public Long getDurationMs() { return durationMs; }
    public String getOwnerTenantId() { return ownerTenantId; }
    public long getCreatedAt() { return createdAt; }
    public Long getExpiresAt() { return expiresAt; }
    public Map<String, String> getMetadata() { return metadata; }
}
