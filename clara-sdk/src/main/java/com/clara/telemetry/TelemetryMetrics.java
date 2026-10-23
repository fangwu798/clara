package com.clara.telemetry;

import com.clara.core.telemetry.ITelemetrySink;
import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetryStatusEnum;
import com.clara.usage.TokenCountFormatter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 从 Span 事件流聚合低基数指标：对话、模型、工具、上下文、工作流与 MCP 发现。
 *
 * <p>为什么从 Span 派生指标：Trace 已经是事实来源，指标若再手写一套计数点，容易出现「日志里有、看板上没有」。
 * 本类只消费 {@link TelemetryEvent} 的名称、属性、状态和耗时，不接触提示词正文，也不增加业务代码的埋点负担。
 *
 * <p>基数约束：标签只使用固定枚举或配置中有限的名称（provider / model / tool / workflow / node_type 等），
 * 禁止把 traceId、conversationId、用户输入、文件路径作为标签。
 *
 * <p>采样关系：指标接收端位于采样器之前，因此成功链路即使被 10% 采样丢弃，token 与错误计数仍然完整。
 */
public final class TelemetryMetrics implements ITelemetrySink {

    /** 计数器集合；键是「指标名 + 标签」。 */
    private final ConcurrentMap<MetricSeries, LongAdder> counters = new ConcurrentHashMap<>();

    /** 直方图集合；键是「指标名 + 标签」。 */
    private final ConcurrentMap<MetricSeries, MetricHistogram> histograms = new ConcurrentHashMap<>();

    @Override
    public void onSpan(TelemetryEvent event) {
        if (event == null) {
            return;
        }
        try {
            collect(event);
        } catch (RuntimeException ignored) {
            // 指标是旁路：单条事件解析失败不能影响对话或其它接收端。
        }
    }

    /**
     * 渲染 Prometheus 文本格式。
     *
     * @return 可直接被 Prometheus 抓取的文本，非 null
     */
    public String prometheus() {
        return PrometheusMetricsFormatter.format(counterSnapshot(), histogramSnapshot());
    }

    /**
     * 渲染面向终端的中文摘要，供 {@code /usage} 使用。
     *
     * @return 多行文本，非 null
     */
    public String report() {
        StringBuilder report = new StringBuilder("可观测指标（本次进程）：");
        long turns = histogramCount("clara.turn.duration");
        long failedTurns = histogramCount("clara.turn.duration", "status", "ERROR")
                + histogramCount("clara.turn.duration", "status", "CANCELLED");
        report.append('\n').append("  对话轮次 ").append(turns)
                .append("（失败 ").append(failedTurns).append("），P95 耗时 ")
                .append(duration(aggregate("clara.turn.duration").p95(), turns));
        long modelCalls = counter("clara.model.calls");
        long modelErrors = counter("clara.model.errors");
        report.append('\n').append("  模型调用 ").append(modelCalls)
                .append("（错误 ").append(modelErrors).append("），输入 ")
                .append(TokenCountFormatter.format(counter("clara.model.tokens", "direction", "input")))
                .append(" / 输出 ")
                .append(TokenCountFormatter.format(counter("clara.model.tokens", "direction", "output")))
                .append(" tokens，首字 P95 ")
                .append(duration(aggregate("clara.model.first_token_millis").p95(),
                        histogramCount("clara.model.first_token_millis")));
        long toolCalls = counter("clara.tool.calls");
        long toolErrors = counter("clara.tool.calls", "status", "ERROR");
        report.append('\n').append("  工具调用 ").append(toolCalls)
                .append("（错误 ").append(toolErrors).append("），P95 耗时 ")
                .append(duration(aggregate("clara.tool.duration").p95(),
                        histogramCount("clara.tool.duration")));
        report.append('\n').append("  上下文：装配 token P95 ")
                .append(TokenCountFormatter.format(aggregate("clara.context.tokens").p95()))
                .append("，压缩 ").append(counter("clara.context.compression")).append(" 次")
                .append("，召回命中 ").append(counter("clara.recall.hits")).append(" 条")
                .append("，超限重试 ").append(counter("clara.context.overflow")).append(" 次");
        long workflowNodes = histogramCount("clara.workflow.node.duration");
        long workflowErrors = counter("clara.workflow.node.errors");
        report.append('\n').append("  工作流节点 ").append(workflowNodes)
                .append("（失败 ").append(workflowErrors).append("），P95 耗时 ")
                .append(duration(aggregate("clara.workflow.node.duration").p95(), workflowNodes));
        long mcpDiscoveries = histogramCount("clara.mcp.discovery.duration_millis");
        report.append('\n').append("  MCP 发现 ").append(mcpDiscoveries)
                .append(" 次（失败 ").append(counter("clara.mcp.discovery.errors"))
                .append("），P95 耗时 ")
                .append(duration(aggregate("clara.mcp.discovery.duration_millis").p95(), mcpDiscoveries))
                .append("；故障转移 ").append(counter("clara.failover")).append(" 次");
        return report.toString();
    }

    /**
     * 清空全部指标；主要用于测试与显式的会话重置。
     */
    public void clear() {
        counters.clear();
        histograms.clear();
    }

    private void collect(TelemetryEvent event) {
        String status = status(event);
        String model = attr(event, "model");
        String provider = attr(event, "provider");
        String stage = attr(event, "stage");
        if ("user.turn".equals(event.getName())) {
            histogram("clara.turn.duration", labels("status", status), event.getDurationMillis());
            String from = attr(event, "failover.from");
            String to = attr(event, "failover.to");
            if (!"unknown".equals(from) && !"unknown".equals(to)) {
                counter("clara.failover", labels("from", from, "to", to));
            }
            return;
        }
        if ("model.call".equals(event.getName())) {
            Map<String, String> base = labels("provider", provider, "model", model,
                    "stage", stage, "status", status);
            counter("clara.model.calls", base);
            histogram("clara.model.duration", base, event.getDurationMillis());
            long input = longAttr(event, "input_tokens");
            long output = longAttr(event, "output_tokens");
            if (input > 0L) {
                counter("clara.model.tokens", labels("provider", provider, "model", model,
                        "stage", stage, "direction", "input"), input);
            }
            if (output > 0L) {
                counter("clara.model.tokens", labels("provider", provider, "model", model,
                        "stage", stage, "direction", "output"), output);
            }
            Long firstToken = nullableLongAttr(event, "first_token_ms");
            if (firstToken != null) {
                histogram("clara.model.first_token_millis", labels("provider", provider, "model", model,
                        "stage", stage), firstToken);
            }
            if (TelemetryStatusEnum.ERROR.name().equals(status)) {
                counter("clara.model.errors", labels("provider", provider, "model", model,
                        "stage", stage, "category", attr(event, "errorCategory")));
            }
            return;
        }
        if ("context.assemble".equals(event.getName())) {
            Long tokens = nullableLongAttr(event, "tokens");
            if (tokens != null) {
                histogram("clara.context.tokens", labels("stage", "assemble"), tokens);
            }
            return;
        }
        if ("context.recall".equals(event.getName())) {
            String source = attr(event, "source");
            counter("clara.recall.hits", labels("source", source), longAttr(event, "items"));
            counter("clara.recall.candidates", labels("source", source), longAttr(event, "candidates"));
            return;
        }
        if ("context.compress".equals(event.getName())) {
            if ("true".equals(attr(event, "summary"))) {
                counter("clara.context.compression", labels("reason", attr(event, "reason")));
            }
            return;
        }
        if ("context.overflow".equals(event.getName())) {
            counter("clara.context.overflow", labels("reason", attr(event, "reason")));
            return;
        }
        if ("tool.call".equals(event.getName())) {
            String tool = attr(event, "tool");
            String route = attr(event, "route");
            Map<String, String> base = labels("tool", tool, "route", route, "status", status);
            counter("clara.tool.calls", base);
            histogram("clara.tool.duration", labels("tool", tool, "route", route, "status", status),
                    event.getDurationMillis());
            return;
        }
        if ("workflow.execute".equals(event.getName())) {
            String workflow = attr(event, "workflow");
            counter("clara.workflow.executions", labels("workflow", workflow, "status", status));
            histogram("clara.workflow.duration", labels("workflow", workflow, "status", status),
                    event.getDurationMillis());
            if (TelemetryStatusEnum.ERROR.name().equals(status)) {
                counter("clara.workflow.errors", labels("workflow", workflow,
                        "category", attr(event, "errorCategory")));
            }
            return;
        }
        if ("workflow.node".equals(event.getName())) {
            String workflow = attr(event, "workflow");
            String nodeType = attr(event, "node_type");
            histogram("clara.workflow.node.duration", labels("workflow", workflow,
                    "node_type", nodeType, "status", status), event.getDurationMillis());
            if (TelemetryStatusEnum.ERROR.name().equals(status)) {
                counter("clara.workflow.node.errors", labels("workflow", workflow,
                        "node_type", nodeType, "category", attr(event, "errorCategory")));
            }
            return;
        }
        if ("mcp.discovery".equals(event.getName())) {
            String server = attr(event, "server");
            histogram("clara.mcp.discovery.duration_millis", labels("server", server, "status", status),
                    event.getDurationMillis());
            if (TelemetryStatusEnum.ERROR.name().equals(status)) {
                counter("clara.mcp.discovery.errors", labels("server", server,
                        "category", attr(event, "errorCategory")));
            }
            return;
        }
        if ("mcp.load".equals(event.getName())) {
            counter("clara.mcp.loads", labels("status", status));
        }
    }

    private void counter(String name, Map<String, String> labels) {
        counter(name, labels, 1L);
    }

    private void counter(String name, Map<String, String> labels, long delta) {
        if (delta <= 0L) {
            return;
        }
        MetricSeries series = MetricSeries.of(name, labels);
        LongAdder adder = counters.get(series);
        if (adder == null) {
            LongAdder created = new LongAdder();
            LongAdder previous = counters.putIfAbsent(series, created);
            adder = previous == null ? created : previous;
        }
        adder.add(delta);
    }

    private void histogram(String name, Map<String, String> labels, long value) {
        MetricSeries series = MetricSeries.of(name, labels);
        MetricHistogram histogram = histograms.get(series);
        if (histogram == null) {
            MetricHistogram created = new MetricHistogram();
            MetricHistogram previous = histograms.putIfAbsent(series, created);
            histogram = previous == null ? created : previous;
        }
        histogram.record(value);
    }

    private static Map<String, String> labels(String... keyValues) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (keyValues == null) {
            return labels;
        }
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            labels.put(keyValues[index], keyValues[index + 1]);
        }
        return labels;
    }

    private static String status(TelemetryEvent event) {
        TelemetryStatusEnum status = event.getStatus();
        return status == null ? TelemetryStatusEnum.OK.name() : status.name();
    }

    private static String attr(TelemetryEvent event, String key) {
        String value = event.getAttributes().get(key);
        return value == null || value.trim().isEmpty() ? "unknown" : value;
    }

    private static long longAttr(TelemetryEvent event, String key) {
        Long value = nullableLongAttr(event, key);
        return value == null ? 0L : value;
    }

    private static Long nullableLongAttr(TelemetryEvent event, String key) {
        String value = event.getAttributes().get(key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    Map<MetricSeries, Long> counterSnapshot() {
        Map<MetricSeries, Long> snapshot = new LinkedHashMap<>();
        List<MetricSeries> series = new ArrayList<>(counters.keySet());
        Collections.sort(series);
        for (MetricSeries key : series) {
            snapshot.put(key, counters.get(key).sum());
        }
        return snapshot;
    }

    Map<MetricSeries, HistogramSnapshot> histogramSnapshot() {
        Map<MetricSeries, HistogramSnapshot> snapshot = new LinkedHashMap<>();
        List<MetricSeries> series = new ArrayList<>(histograms.keySet());
        Collections.sort(series);
        for (MetricSeries key : series) {
            snapshot.put(key, histograms.get(key).snapshot());
        }
        return snapshot;
    }

    private long counter(String name) {
        return counter(name, null, null);
    }

    private long counter(String name, String label, String value) {
        long total = 0L;
        for (Map.Entry<MetricSeries, Long> entry : counterSnapshot().entrySet()) {
            if (!entry.getKey().getName().equals(name)) {
                continue;
            }
            if (label != null && !value.equals(entry.getKey().label(label))) {
                continue;
            }
            total += entry.getValue();
        }
        return total;
    }

    private long histogramCount(String name) {
        return histogramCount(name, null, null);
    }

    private long histogramCount(String name, String label, String value) {
        long total = 0L;
        for (Map.Entry<MetricSeries, HistogramSnapshot> entry : histogramSnapshot().entrySet()) {
            if (!entry.getKey().getName().equals(name)) {
                continue;
            }
            if (label != null && !value.equals(entry.getKey().label(label))) {
                continue;
            }
            total += entry.getValue().getCount();
        }
        return total;
    }

    private HistogramSnapshot aggregate(String name) {
        long[] buckets = new long[MetricHistogram.BUCKETS.length + 1];
        long count = 0L;
        long sum = 0L;
        long max = 0L;
        for (Map.Entry<MetricSeries, HistogramSnapshot> entry : histogramSnapshot().entrySet()) {
            if (!entry.getKey().getName().equals(name)) {
                continue;
            }
            HistogramSnapshot part = entry.getValue();
            long[] partBuckets = part.getBuckets();
            for (int index = 0; index < buckets.length; index++) {
                buckets[index] += partBuckets[index];
            }
            count += part.getCount();
            sum += part.getSum();
            max = Math.max(max, part.getMax());
        }
        return new HistogramSnapshot(buckets, count, sum, max);
    }

    private static String duration(long millis, long count) {
        if (count <= 0L) {
            return "-";
        }
        if (millis < 1000L) {
            return millis + "ms";
        }
        if (millis < 60_000L) {
            return String.format(Locale.ROOT, "%.1fs", millis / 1000.0d);
        }
        return String.format(Locale.ROOT, "%.1fmin", millis / 60_000.0d);
    }
}