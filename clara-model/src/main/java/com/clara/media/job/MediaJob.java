package com.clara.media.job;

/**
 * 表示。
 */
public final class MediaJob {
    private final String jobId;
    private final String tenantId;
    private final MediaJobStatus status;
    private final long version;
    private final String providerJobId;
    private final int progress;
    private final String errorCode;
    private final String outputAssetId;
    private final long createdAt;
    private final long updatedAt;
    private final String idempotencyKey;
    private final boolean submissionAttempted;
    private final int pollAttempts;
    private final Long nextPollAt;
    private final Long deadlineAt;

    public MediaJob(String jobId, String tenantId, MediaJobStatus status, long version,
            String providerJobId, int progress, String errorCode, String outputAssetId,
            long createdAt, long updatedAt) {
        this(jobId, tenantId, status, version, providerJobId, progress, errorCode, outputAssetId,
                createdAt, updatedAt, null, false, 0, null, null);
    }

    public MediaJob(String jobId, String tenantId, MediaJobStatus status, long version,
            String providerJobId, int progress, String errorCode, String outputAssetId,
            long createdAt, long updatedAt, String idempotencyKey, boolean submissionAttempted,
            int pollAttempts, Long nextPollAt, Long deadlineAt) {
        this.jobId = required(jobId, "jobId");
        this.tenantId = required(tenantId, "tenantId");
        if (status == null) { throw new IllegalArgumentException("status must not be null"); }
        if (version < 0) { throw new IllegalArgumentException("version must not be negative"); }
        if (progress < 0 || progress > 100) { throw new IllegalArgumentException("progress must be between 0 and 100"); }
        if (status == MediaJobStatus.SUCCEEDED && (outputAssetId == null || outputAssetId.trim().isEmpty())) {
            throw new IllegalArgumentException("successful job requires outputAssetId");
        }
        if (status == MediaJobStatus.FAILED && (errorCode == null || errorCode.trim().isEmpty())) {
            throw new IllegalArgumentException("failed job requires errorCode");
        }
        if (providerJobId != null && providerJobId.trim().isEmpty()) {
            throw new IllegalArgumentException("providerJobId must not be blank");
        }
        if (idempotencyKey != null && idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (pollAttempts < 0) { throw new IllegalArgumentException("pollAttempts must not be negative"); }
        if (deadlineAt != null && deadlineAt.longValue() <= createdAt) {
            throw new IllegalArgumentException("deadlineAt must be after createdAt");
        }
        if (updatedAt < createdAt) { throw new IllegalArgumentException("updatedAt must not precede createdAt"); }
        if (nextPollAt != null && (nextPollAt.longValue() < createdAt
                || (deadlineAt != null && nextPollAt.longValue() > deadlineAt.longValue()))) {
            throw new IllegalArgumentException("nextPollAt is outside the job lifetime");
        }
        this.status = status;
        this.version = version;
        this.providerJobId = providerJobId;
        this.progress = progress;
        this.errorCode = errorCode;
        this.outputAssetId = outputAssetId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.idempotencyKey = idempotencyKey;
        this.submissionAttempted = submissionAttempted;
        this.pollAttempts = pollAttempts;
        this.nextPollAt = nextPollAt;
        this.deadlineAt = deadlineAt;
    }

    public static MediaJob queued(String jobId, String tenantId, long now) {
        return new MediaJob(jobId, tenantId, MediaJobStatus.QUEUED, 0, null, 0, null, null, now, now);
    }
    public static MediaJob queued(String jobId, String tenantId, String idempotencyKey,
            long deadlineAt, long now) {
        required(idempotencyKey, "idempotencyKey");
        return new MediaJob(jobId, tenantId, MediaJobStatus.QUEUED, 0, null, 0, null, null,
                now, now, idempotencyKey, false, 0, null, Long.valueOf(deadlineAt));
    }
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException(name + " must not be blank"); }
        return value;
    }
    public String getJobId() { return jobId; }
    public String getTenantId() { return tenantId; }
    public MediaJobStatus getStatus() { return status; }
    public long getVersion() { return version; }
    public String getProviderJobId() { return providerJobId; }
    public int getProgress() { return progress; }
    public String getErrorCode() { return errorCode; }
    public String getOutputAssetId() { return outputAssetId; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public boolean isSubmissionAttempted() { return submissionAttempted; }
    public int getPollAttempts() { return pollAttempts; }
    public Long getNextPollAt() { return nextPollAt; }
    public Long getDeadlineAt() { return deadlineAt; }
}
