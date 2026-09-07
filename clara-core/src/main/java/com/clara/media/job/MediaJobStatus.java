package com.clara.media.job;

/**
 * 定义媒体任务的状态取值。
 */
public enum MediaJobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, EXPIRED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }
}
