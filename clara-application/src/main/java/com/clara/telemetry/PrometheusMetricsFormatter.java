package com.clara.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 把指标快照渲染为 Prometheus 文本暴露格式（version 0.0.4）。
 *
 * <p>只做格式化：不聚合、不采样、不访问网络。计数器自动追加 {@code _total}，直方图输出
 * {@code _bucket / _count / _sum} 三组序列；桶计数在这里转成累计值，符合 Prometheus 约定。
 */
final class PrometheusMetricsFormatter {

    private PrometheusMetricsFormatter() {
    }

    /**
     * 渲染全部指标。
     *
     * @param counters   计数器快照，非 null
     * @param histograms 直方图快照，非 null
     * @return Prometheus 文本，非 null
     */
    static String format(Map<MetricSeries, Long> counters, Map<MetricSeries, HistogramSnapshot> histograms) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<MetricSeries, Long> entry : counters.entrySet()) {
            MetricSeries series = entry.getKey();
            String name = counterName(series);
            text.append("# TYPE ").append(name).append(" counter\n");
            text.append(name).append(labels(series.getLabels(), null, null))
                    .append(' ').append(entry.getValue()).append('\n');
        }
        for (Map.Entry<MetricSeries, HistogramSnapshot> entry : histograms.entrySet()) {
            MetricSeries series = entry.getKey();
            String name = series.prometheusName();
            text.append("# TYPE ").append(name).append(" histogram\n");
            appendBuckets(text, series, entry.getValue());
            text.append(name).append("_count").append(labels(series.getLabels(), null, null))
                    .append(' ').append(entry.getValue().getCount()).append('\n');
            text.append(name).append("_sum").append(labels(series.getLabels(), null, null))
                    .append(' ').append(entry.getValue().getSum()).append('\n');
        }
        return text.toString();
    }

    private static void appendBuckets(StringBuilder text, MetricSeries series, HistogramSnapshot snapshot) {
        long[] buckets = snapshot.getBuckets();
        long cumulative = 0L;
        for (int index = 0; index < MetricHistogram.BUCKETS.length; index++) {
            cumulative += buckets[index];
            text.append(series.prometheusName()).append("_bucket")
                    .append(labels(series.getLabels(), "le", Long.toString(MetricHistogram.BUCKETS[index])))
                    .append(' ').append(cumulative).append('\n');
        }
        cumulative += buckets[buckets.length - 1];
        text.append(series.prometheusName()).append("_bucket")
                .append(labels(series.getLabels(), "le", "+Inf"))
                .append(' ').append(cumulative).append('\n');
    }

    private static String counterName(MetricSeries series) {
        String name = series.prometheusName();
        return name.endsWith("_total") ? name : name + "_total";
    }

    private static String labels(Map<String, String> labels, String extraKey, String extraValue) {
        List<String> keys = new ArrayList<>(labels.keySet());
        Collections.sort(keys);
        StringBuilder text = new StringBuilder();
        for (String key : keys) {
            appendLabel(text, key, labels.get(key));
        }
        if (extraKey != null) {
            appendLabel(text, extraKey, extraValue);
        }
        return text.length() == 0 ? "" : "{" + text + "}";
    }

    private static void appendLabel(StringBuilder text, String key, String value) {
        if (text.length() > 0) {
            text.append(',');
        }
        text.append(key).append("=\"").append(escape(value)).append('"');
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}