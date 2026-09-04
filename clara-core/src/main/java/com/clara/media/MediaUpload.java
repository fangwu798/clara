package com.clara.media;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MediaUpload {
    private final String tenantId;
    private final MediaType mediaType;
    private final String fileName;
    private final String mimeType;
    private final String sourceUri;
    private final Integer width;
    private final Integer height;
    private final Long durationMs;
    private final Long expiresAt;
    private final Map<String, String> metadata;
    private final MediaInput input;

    public MediaUpload(String tenantId, MediaType mediaType, String fileName, String mimeType,
            String sourceUri, Integer width, Integer height, Long durationMs, Long expiresAt,
            Map<String, String> metadata, MediaInput input) {
        this.tenantId = required(tenantId, "tenantId");
        if (mediaType == null) throw new IllegalArgumentException("mediaType must not be null");
        this.mediaType = mediaType;
        this.fileName = required(fileName, "fileName");
        this.mimeType = required(mimeType, "mimeType").toLowerCase(java.util.Locale.ROOT);
        if ((width == null) != (height == null)) throw new IllegalArgumentException("width and height must be set together");
        if (width != null && (width.intValue() <= 0 || height.intValue() <= 0))
            throw new IllegalArgumentException("dimensions must be positive");
        if (durationMs != null && durationMs.longValue() < 0) throw new IllegalArgumentException("durationMs must not be negative");
        if (input == null) throw new IllegalArgumentException("input must not be null");
        this.sourceUri = sourceUri;
        this.width = width;
        this.height = height;
        this.durationMs = durationMs;
        this.expiresAt = expiresAt;
        this.metadata = metadata == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(metadata));
        this.input = input;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
    public String getTenantId() { return tenantId; }
    public MediaType getMediaType() { return mediaType; }
    public String getFileName() { return fileName; }
    public String getMimeType() { return mimeType; }
    public String getSourceUri() { return sourceUri; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public Long getDurationMs() { return durationMs; }
    public Long getExpiresAt() { return expiresAt; }
    public Map<String, String> getMetadata() { return metadata; }
    public MediaInput getInput() { return input; }
}
