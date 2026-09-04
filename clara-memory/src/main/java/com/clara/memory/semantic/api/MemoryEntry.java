package com.clara.memory.semantic.api;

import java.util.*;

public final class MemoryEntry {
    private final String id, tenantId, userId, conversationId, domain, sourceId, content, contentHash;
    private final MemorySourceType sourceType;
    private final float[] vector;
    private final long createdAt, expiresAt;
    private final Map<String, String> metadata;

    public MemoryEntry(String id, String tenant, String user, String conversation, String domain, MemorySourceType sourceType, String sourceId, String content, String contentHash, float[] vector, long created, long expires, Map<String, String> metadata) {
        if (blank(id) || blank(tenant) || blank(domain) || sourceType == null || blank(sourceId) || content == null || contentHash == null || !contentHash.matches("[0-9a-f]{64}") || vector == null || vector.length == 0 || created < 0 || expires < 0 || expires > 0 && expires <= created)
            throw new IllegalArgumentException("memory entry is invalid");
        double norm = 0;
        for (float value : vector) {
            if (Float.isNaN(value) || Float.isInfinite(value)) throw new IllegalArgumentException("vector is invalid");
            norm += (double) value * value;
        }
        if (norm == 0) throw new IllegalArgumentException("vector must not be zero");
        this.id = id;
        tenantId = tenant;
        userId = user;
        conversationId = conversation;
        this.domain = domain;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.content = content;
        this.contentHash = contentHash;
        this.vector = vector.clone();
        createdAt = created;
        expiresAt = expires;
        LinkedHashMap<String, String> copied = new LinkedHashMap<String, String>();
        if (metadata != null) for (Map.Entry<String, String> value : metadata.entrySet()) {
            if (blank(value.getKey()) || value.getValue() == null)
                throw new IllegalArgumentException("metadata is invalid");
            copied.put(value.getKey(), value.getValue());
        }
        this.metadata = Collections.unmodifiableMap(copied);
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getDomain() {
        return domain;
    }

    public MemorySourceType getSourceType() {
        return sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getContent() {
        return content;
    }

    public String getContentHash() {
        return contentHash;
    }

    public float[] getVector() {
        return vector.clone();
    }

    public int getDimension() {
        return vector.length;
    }

    public long getCreatedAtEpochMillis() {
        return createdAt;
    }

    public long getExpiresAtEpochMillis() {
        return expiresAt;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public boolean isExpired(long now) {
        return expiresAt > 0 && now >= expiresAt;
    }

    private static boolean blank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
