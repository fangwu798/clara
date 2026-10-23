package com.clara.telemetry;

/**
 * 直方图快照：计数、总和与各桶累计值，供 Prometheus 文本与 {@code /usage} 报告读取。
 *
 * <p>不可变值对象；桶边界由 {@link MetricHistogram#BUCKETS} 统一定义，快照只保存每个桶的计数。
 */
final class HistogramSnapshot {

    /** 桶计数，长度为 {@link MetricHistogram#BUCKETS} 加一个 +Inf 桶。 */
    private final long[] buckets;

    /** 样本总数。 */
    private final long count;

    /** 样本总和。 */
    private final long sum;

    /** 样本最大值，用于 +Inf 桶的 P95 近似。 */
    private final long max;

    HistogramSnapshot(long[] buckets, long count, long sum, long max) {
        this.buckets = buckets.clone();
        this.count = count;
        this.sum = sum;
        this.max = max;
    }

    long getCount() {
        return count;
    }

    long getSum() {
        return sum;
    }

    /** @return 样本最大值；无样本时为 0 */
    long getMax() {
        return max;
    }

    long[] getBuckets() {
        return buckets.clone();
    }

    /**
     * 计算 P95 的近似上界：取累计计数首次达到 95% 的桶上界。
     *
     * <p>直方图不保存原始样本，P95 是桶粒度近似值；用于告警足够，不能当作精确分位数展示。
     *
     * @return P95 上界毫秒；无样本时为 0
     */
    long p95() {
        if (count <= 0L) {
            return 0L;
        }
        long target = (long) Math.ceil(count * 0.95d);
        long cumulative = 0L;
        for (int index = 0; index < MetricHistogram.BUCKETS.length; index++) {
            cumulative += buckets[index];
            if (cumulative >= target) {
                return MetricHistogram.BUCKETS[index];
            }
        }
        // 落在 +Inf 桶：用真实最大值兜底，避免把 45s 的请求误报成 30s。
        return Math.max(max, MetricHistogram.BUCKETS[MetricHistogram.BUCKETS.length - 1]);
    }
}