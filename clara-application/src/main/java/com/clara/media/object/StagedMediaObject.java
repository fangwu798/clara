package com.clara.media.object;

public final class StagedMediaObject {
    private final String key;
    private final long size;
    private final String sha256;

    public StagedMediaObject(String key, long size, String sha256) {
        if (key == null || key.trim().isEmpty()) throw new IllegalArgumentException("key must not be blank");
        if (size < 0) throw new IllegalArgumentException("size must not be negative");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid sha256");
        this.key = key;
        this.size = size;
        this.sha256 = sha256;
    }
    public String getKey() { return key; }
    public long getSize() { return size; }
    public String getSha256() { return sha256; }
}
