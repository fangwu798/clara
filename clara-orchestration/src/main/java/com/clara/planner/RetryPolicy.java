package com.clara.planner;

public final class RetryPolicy {
    public static final RetryPolicy NONE = new RetryPolicy(1, 0L);
    private final int maxAttempts;
    private final long delayMillis;
    public RetryPolicy(int maxAttempts, long delayMillis) {
        if (maxAttempts <= 0 || delayMillis < 0L) throw new IllegalArgumentException("retry policy is invalid");
        this.maxAttempts = maxAttempts; this.delayMillis = delayMillis;
    }
    public int getMaxAttempts() { return maxAttempts; }
    public long getDelayMillis() { return delayMillis; }
}
