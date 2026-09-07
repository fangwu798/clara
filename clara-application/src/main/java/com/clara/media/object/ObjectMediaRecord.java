package com.clara.media.object;

import com.clara.media.MediaAsset;

/**
 * 表示对象媒体的持久化记录。
 */
public final class ObjectMediaRecord {
    private final String key;
    private final long size;
    private final String sha256;
    private final MediaAsset asset;

    public ObjectMediaRecord(String key, long size, String sha256, MediaAsset asset) {
        if (key == null || key.trim().isEmpty()) { throw new IllegalArgumentException("key must not be blank"); }
        if (size < 0) { throw new IllegalArgumentException("size must not be negative"); }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) { throw new IllegalArgumentException("invalid sha256"); }
        if (asset == null) { throw new IllegalArgumentException("asset must not be null"); }
        this.key = key;
        this.size = size;
        this.sha256 = sha256;
        this.asset = asset;
    }
    public String getKey() { return key; }
    public long getSize() { return size; }
    public String getSha256() { return sha256; }
    public MediaAsset getAsset() { return asset; }
}
