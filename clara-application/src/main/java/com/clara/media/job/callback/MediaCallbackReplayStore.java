package com.clara.media.job.callback;

public interface MediaCallbackReplayStore {
    MediaCallbackReplayDecision reserve(String tenantId, String keyId, String nonce,
            long expiresAt, long now, int maxEntries);
}
