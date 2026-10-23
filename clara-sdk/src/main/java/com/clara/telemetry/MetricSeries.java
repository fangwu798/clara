package com.clara.telemetry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条指标序列的唯一标识：指标名 + 标签集合。
 *
 * <p>标签按插入顺序保存，但相等性只取决于键值内容，不依赖顺序；因此同一组标签无论以什么顺序构造，
 * 都会落到同一个计数器 / 直方图上。标签值不允许为 null，空值统一用 {@code unknown} 表示，
 * 避免 Prometheus 文本出现非法标签。
 */
final class MetricSeries implements Comparable<MetricSeries> {

    /** 指标名，使用点号分层，如 {@code clara.model.calls}。 */
    private final String name;

    /** 标签集合，构造后不可变。 */
    private final Map<String, String> labels;

    private MetricSeries(String name, Map<String, String> labels) {
        this.name = name;
        this.labels = labels;
    }

    /**
     * 创建指标序列。
     *
     * @param name   指标名，非空白
     * @param labels 标签集合，可为 null
     * @return 指标序列，非 null
     */
    static MetricSeries of(String name, Map<String, String> labels) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("metric name must not be blank");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        if (labels != null) {
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                    continue;
                }
                String value = entry.getValue();
                copy.put(entry.getKey().trim(), value == null || value.trim().isEmpty() ? "unknown" : value);
            }
        }
        return new MetricSeries(name.trim(), Collections.unmodifiableMap(copy));
    }

    String getName() {
        return name;
    }

    Map<String, String> getLabels() {
        return labels;
    }

    /**
     * 取指定标签值。
     *
     * @param key 标签名
     * @return 标签值；不存在时为 null
     */
    String label(String key) {
        return labels.get(key);
    }

    /**
     * 转成 Prometheus 指标名：点号替换为下划线。
     *
     * @return Prometheus 合法名称，非 null
     */
    String prometheusName() {
        return name.replace('.', '_');
    }

    @Override
    public int compareTo(MetricSeries other) {
        int byName = name.compareTo(other.name);
        if (byName != 0) {
            return byName;
        }
        return labels.toString().compareTo(other.labels.toString());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MetricSeries)) {
            return false;
        }
        MetricSeries that = (MetricSeries) other;
        return name.equals(that.name) && labels.equals(that.labels);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + labels.hashCode();
    }
}