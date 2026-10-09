package com.clara.dto.planner;

/**
 * 重试策略：定义任务的最大尝试次数与重试间隔。
 */
public final class RetryPolicy {

    /** 不重试策略。 */
    public static final RetryPolicy NONE = new RetryPolicy(1, 0L);

    private final int maxAttempts;
    private final long delayMillis;

    /**
     * 创建重试策略。
     */
    public RetryPolicy(int maxAttempts, long delayMillis) {
        if (maxAttempts <= 0 || delayMillis < 0L) {
            throw new IllegalArgumentException("retry policy is invalid");
        }
        this.maxAttempts = maxAttempts;
        this.delayMillis = delayMillis;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getDelayMillis() {
        return delayMillis;
    }
}
