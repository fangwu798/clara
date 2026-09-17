package com.clara.media.job.callback;

/**
 * 定义媒体回调安全需要遵循的策略。
 */
public final class MediaCallbackSecurityPolicy {
    private final long maxClockSkewMillis;
    private final int maxPayloadBytes;
    private final int maxIdentityChars;
    private final int maxNonceChars;
    private final int maxTrackedNonces;

    public MediaCallbackSecurityPolicy(long maxClockSkewMillis, int maxPayloadBytes,
                                       int maxIdentityChars, int maxNonceChars, int maxTrackedNonces) {
        if (maxClockSkewMillis <= 0L) {
            throw new IllegalArgumentException("maxClockSkewMillis must be positive");
        }
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        if (maxIdentityChars <= 0) {
            throw new IllegalArgumentException("maxIdentityChars must be positive");
        }
        if (maxNonceChars <= 0) {
            throw new IllegalArgumentException("maxNonceChars must be positive");
        }
        if (maxTrackedNonces <= 0) {
            throw new IllegalArgumentException("maxTrackedNonces must be positive");
        }
        this.maxClockSkewMillis = maxClockSkewMillis;
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxIdentityChars = maxIdentityChars;
        this.maxNonceChars = maxNonceChars;
        this.maxTrackedNonces = maxTrackedNonces;
    }

    public long getMaxClockSkewMillis() {
        return maxClockSkewMillis;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public int getMaxIdentityChars() {
        return maxIdentityChars;
    }

    public int getMaxNonceChars() {
        return maxNonceChars;
    }

    public int getMaxTrackedNonces() {
        return maxTrackedNonces;
    }
}
