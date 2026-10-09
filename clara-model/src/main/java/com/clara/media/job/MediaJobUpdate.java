package com.clara.media.job;

/**
 * 描述对媒体任务的一次更新。
 */
public final class MediaJobUpdate {
    private final String providerJobId;
    private final Integer progress;
    private final String errorCode;
    private final String outputAssetId;
    private final Boolean submissionAttempted;
    private final Integer pollAttempts;
    private final Long nextPollAt;

    public MediaJobUpdate(String providerJobId, Integer progress, String errorCode, String outputAssetId) {
        this(providerJobId, progress, errorCode, outputAssetId, null, null, null);
    }
    public MediaJobUpdate(String providerJobId, Integer progress, String errorCode, String outputAssetId,
            Boolean submissionAttempted, Integer pollAttempts, Long nextPollAt) {
        if (progress != null && (progress.intValue() < 0 || progress.intValue() > 100)) {
            throw new IllegalArgumentException("progress must be between 0 and 100");
        }
        if (pollAttempts != null && pollAttempts.intValue() < 0) {
            throw new IllegalArgumentException("pollAttempts must not be negative");
        }
        this.providerJobId = providerJobId;
        this.progress = progress;
        this.errorCode = errorCode;
        this.outputAssetId = outputAssetId;
        this.submissionAttempted = submissionAttempted;
        this.pollAttempts = pollAttempts;
        this.nextPollAt = nextPollAt;
    }
    public static MediaJobUpdate none() { return new MediaJobUpdate(null, null, null, null); }
    public String getProviderJobId() { return providerJobId; }
    public Integer getProgress() { return progress; }
    public String getErrorCode() { return errorCode; }
    public String getOutputAssetId() { return outputAssetId; }
    public Boolean getSubmissionAttempted() { return submissionAttempted; }
    public Integer getPollAttempts() { return pollAttempts; }
    public Long getNextPollAt() { return nextPollAt; }
}
