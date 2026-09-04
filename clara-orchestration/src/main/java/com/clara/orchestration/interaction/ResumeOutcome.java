package com.clara.orchestration.interaction;

public final class ResumeOutcome {
    private final String instanceStatus;
    private final long instanceVersion;
    public ResumeOutcome(String instanceStatus, long instanceVersion) {
        if (instanceStatus == null || instanceStatus.trim().isEmpty() || instanceVersion < 0L) {
            throw new IllegalArgumentException("resume outcome is invalid");
        }
        this.instanceStatus = instanceStatus; this.instanceVersion = instanceVersion;
    }
    public String getInstanceStatus() { return instanceStatus; }
    public long getInstanceVersion() { return instanceVersion; }
}
