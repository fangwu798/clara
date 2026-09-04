package com.clara.media.job.callback;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class InMemoryMediaCallbackReplayStore implements MediaCallbackReplayStore {
    private final Map<String, Long> expirations = new HashMap<String, Long>();

    public synchronized MediaCallbackReplayDecision reserve(String tenantId, String keyId,
            String nonce, long expiresAt, long now, int maxEntries) {
        if (tenantId == null || keyId == null || nonce == null || expiresAt < 0L
                || now < 0L || maxEntries <= 0)
            throw new IllegalArgumentException("invalid replay reservation");
        Iterator<Map.Entry<String, Long>> iterator = expirations.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().longValue() < now) iterator.remove();
        }
        String key = key(tenantId, keyId, nonce);
        if (expirations.containsKey(key)) return MediaCallbackReplayDecision.REPLAYED;
        if (expirations.size() >= maxEntries) return MediaCallbackReplayDecision.CAPACITY_EXCEEDED;
        expirations.put(key, Long.valueOf(expiresAt));
        return MediaCallbackReplayDecision.ACCEPTED;
    }

    private static String key(String tenantId, String keyId, String nonce) {
        return tenantId.length() + ":" + tenantId + keyId.length() + ":" + keyId + nonce;
    }
}
