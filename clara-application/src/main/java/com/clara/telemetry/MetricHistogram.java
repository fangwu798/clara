package com.clara.telemetry;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * 无锁直方图：按固定桶边界累计耗时 / 数量，适配 Prometheus histogram。
 *
 * <p>桶边界固定为 10ms ~ 30s，覆盖首字符、模型调用、工具调用与工作流节点的常见量级；超出最大边界的样本
 * 计入 {@code +Inf}。使用 {@link LongAdder} 降低高频写入的竞争，快照读取允许瞬时不一致，指标不需要事务语义。
 */
final class MetricHistogram {

    /** 桶上界（毫秒），最后一个桶之后为 +Inf。 */
    static final long[] BUCKETS = {10L, 25L, 50L, 100L, 200L, 500L, 1000L, 2000L, 5000L, 10000L, 30000L};

    /** 每个桶的计数；最后一个元素是 +Inf。 */
    private final LongAdder[] counts = new LongAdder[BUCKETS.length + 1];

    /** 样本总数。 */
    private final LongAdder count = new LongAdder();

    /** 样本总和。 */
    private final LongAdder sum = new LongAdder();

    /** 最大值，用于 /usage 的可读摘要。 */
    private final LongAccumulator max = new LongAccumulator(Math::max, 0L);

    MetricHistogram() {
        for (int index = 0; index < counts.length; index++) {
            counts[index] = new LongAdder();
        }
    }

    /**
     * 记录一个非负样本。
     *
     * @param value 样本值，负数按 0 处理
     */
    void record(long value) {
        long normalized = Math.max(0L, value);
        count.increment();
        sum.add(normalized);
        max.accumulate(normalized);
        for (int index = 0; index < BUCKETS.length; index++) {
            if (normalized <= BUCKETS[index]) {
                counts[index].increment();
                return;
            }
        }
        counts[counts.length - 1].increment();
    }

    /**
     * 取当前快照。
     *
     * @return 不可变快照，非 null
     */
    HistogramSnapshot snapshot() {
        long[] values = new long[counts.length];
        for (int index = 0; index < counts.length; index++) {
            values[index] = counts[index].sum();
        }
        return new HistogramSnapshot(values, count.sum(), sum.sum(), max.get());
    }

    long maximum() {
        return max.get();
    }
}