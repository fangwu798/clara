package com.clara.media.job.callback;

/**
 * 存储并读取媒体回调重放。
 */
public interface MediaCallbackReplayStore {
    MediaCallbackReplayDecision reserve(String tenantId, String keyId, String nonce,
                                        long expiresAt, long now, int maxEntries);
}
