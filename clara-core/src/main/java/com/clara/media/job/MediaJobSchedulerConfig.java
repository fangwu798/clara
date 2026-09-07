package com.clara.media.job;

/**
 * 定义媒体任务调度器的配置。
 */
public final class MediaJobSchedulerConfig {
    private final long initialPollDelayMs;
    private final long maxPollDelayMs;
    private final double backoffMultiplier;
    private final int maxPollAttempts;

    public MediaJobSchedulerConfig(long initialPollDelayMs, long maxPollDelayMs,
            double backoffMultiplier, int maxPollAttempts) {
        if (initialPollDelayMs <= 0 || maxPollDelayMs < initialPollDelayMs
                || backoffMultiplier < 1 || Double.isNaN(backoffMultiplier)
                || Double.isInfinite(backoffMultiplier) || maxPollAttempts <= 0) {
            throw new IllegalArgumentException("invalid scheduler limits");
        }
        this.initialPollDelayMs = initialPollDelayMs;
        this.maxPollDelayMs = maxPollDelayMs;
        this.backoffMultiplier = backoffMultiplier;
        this.maxPollAttempts = maxPollAttempts;
    }
    public long getInitialPollDelayMs() { return initialPollDelayMs; }
    public long getMaxPollDelayMs() { return maxPollDelayMs; }
    public double getBackoffMultiplier() { return backoffMultiplier; }
    public int getMaxPollAttempts() { return maxPollAttempts; }
}
