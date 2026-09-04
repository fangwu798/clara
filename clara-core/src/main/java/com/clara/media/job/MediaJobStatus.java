package com.clara.media.job;

public enum MediaJobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, EXPIRED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }
}
