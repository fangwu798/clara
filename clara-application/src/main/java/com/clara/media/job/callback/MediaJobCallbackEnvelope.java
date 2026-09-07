package com.clara.media.job.callback;

/**
 * 封装媒体任务回调载荷及其传输元数据。
 */
public final class MediaJobCallbackEnvelope {
    private final String tenantId;
    private final String jobId;
    private final long expectedVersion;
    private final String keyId;
    private final long timestampEpochMillis;
    private final String nonce;
    private final byte[] payload;
    private final String signature;

    public MediaJobCallbackEnvelope(String tenantId, String jobId, long expectedVersion,
            String keyId, long timestampEpochMillis, String nonce, byte[] payload,
            String signature) {
        this.tenantId = required(tenantId, "tenantId");
        this.jobId = required(jobId, "jobId");
        if (expectedVersion < 0L) { throw new IllegalArgumentException("expectedVersion must not be negative"); }
        this.keyId = required(keyId, "keyId");
        if (timestampEpochMillis < 0L) {
            throw new IllegalArgumentException("timestampEpochMillis must not be negative");
        }
        this.nonce = required(nonce, "nonce");
        if (payload == null) { throw new IllegalArgumentException("payload must not be null"); }
        this.payload = payload.clone();
        this.signature = required(signature, "signature");
        this.expectedVersion = expectedVersion;
        this.timestampEpochMillis = timestampEpochMillis;
    }

    public String getTenantId() { return tenantId; }
    public String getJobId() { return jobId; }
    public long getExpectedVersion() { return expectedVersion; }
    public String getKeyId() { return keyId; }
    public long getTimestampEpochMillis() { return timestampEpochMillis; }
    public String getNonce() { return nonce; }
    public byte[] getPayload() { return payload.clone(); }
    public String getSignature() { return signature; }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
