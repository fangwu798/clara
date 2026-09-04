package com.clara.media.job;

import com.clara.media.MediaUpload;

public final class MediaProviderJobSnapshot {
    private final MediaProviderJobStatus status;
    private final int progress;
    private final String errorCode;
    private final MediaUpload output;

    public MediaProviderJobSnapshot(MediaProviderJobStatus status, int progress,
            String errorCode, MediaUpload output) {
        if (status == null) throw new IllegalArgumentException("status must not be null");
        if (progress < 0 || progress > 100) throw new IllegalArgumentException("progress must be between 0 and 100");
        if (status == MediaProviderJobStatus.SUCCEEDED && output == null)
            throw new IllegalArgumentException("successful provider job requires output");
        if (status == MediaProviderJobStatus.FAILED && (errorCode == null || errorCode.trim().isEmpty()))
            throw new IllegalArgumentException("failed provider job requires errorCode");
        this.status = status;
        this.progress = progress;
        this.errorCode = errorCode;
        this.output = output;
    }
    public MediaProviderJobStatus getStatus() { return status; }
    public int getProgress() { return progress; }
    public String getErrorCode() { return errorCode; }
    public MediaUpload getOutput() { return output; }
}
