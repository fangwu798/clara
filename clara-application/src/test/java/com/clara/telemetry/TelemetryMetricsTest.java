package com.clara.telemetry;

import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.core.telemetry.TelemetryStatusEnum;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 指标聚合测试：事件到 Prometheus 文本与 /usage 摘要的映射。
 */
public class TelemetryMetricsTest {

    @Test
    public void aggregatesModelTurnToolAndWorkflowMetrics() {
        TelemetryMetrics metrics = new TelemetryMetrics();
        metrics.onSpan(event("user.turn", TelemetrySpanKindEnum.TURN, TelemetryStatusEnum.OK, 1200L));
        metrics.onSpan(event("model.call", TelemetrySpanKindEnum.MODEL, TelemetryStatusEnum.OK, 800L,
                "provider", "DEEPSEEK", "model", "deepseek-v4-pro", "stage", "chat",
                "input_tokens", "120", "output_tokens", "30", "first_token_ms", "150"));
        metrics.onSpan(event("tool.call", TelemetrySpanKindEnum.TOOL, TelemetryStatusEnum.ERROR, 50L,
                "tool", "code_graph", "route", "mcp", "errorCategory", "EXTERNAL_SERVICE"));
        metrics.onSpan(event("context.assemble", TelemetrySpanKindEnum.CONTEXT, TelemetryStatusEnum.OK, 90L,
                "tokens", "2048"));
        metrics.onSpan(event("context.compress", TelemetrySpanKindEnum.CONTEXT, TelemetryStatusEnum.OK, 300L,
                "reason", "window", "summary", "true"));
        metrics.onSpan(event("context.recall", TelemetrySpanKindEnum.CONTEXT, TelemetryStatusEnum.OK, 20L,
                "source", "semantic_recall", "items", "2", "candidates", "7"));
        metrics.onSpan(event("workflow.node", TelemetrySpanKindEnum.WORKFLOW, TelemetryStatusEnum.ERROR, 70L,
                "workflow", "design_business_solution", "node_type", "AGENT",
                "errorCategory", "EXTERNAL_SERVICE"));
        metrics.onSpan(event("mcp.discovery", TelemetrySpanKindEnum.OTHER, TelemetryStatusEnum.OK, 430L,
                "server", "codegraph", "tools", "3"));

        String prometheus = metrics.prometheus();
        assertFalse(prometheus.contains("clara_clara_"));
        assertTrue(prometheus.contains("clara_model_calls_total{model=\"deepseek-v4-pro\",provider=\"DEEPSEEK\",stage=\"chat\",status=\"OK\"} 1"));
        assertTrue(prometheus.contains("clara_model_tokens_total{direction=\"input\",model=\"deepseek-v4-pro\",provider=\"DEEPSEEK\",stage=\"chat\"} 120"));
        assertTrue(prometheus.contains("clara_model_first_token_millis_count{model=\"deepseek-v4-pro\",provider=\"DEEPSEEK\",stage=\"chat\"} 1"));
        assertTrue(prometheus.contains("clara_tool_calls_total{route=\"mcp\",status=\"ERROR\",tool=\"code_graph\"} 1"));
        assertTrue(prometheus.contains("clara_context_compression_total{reason=\"window\"} 1"));
        assertTrue(prometheus.contains("clara_recall_hits_total{source=\"semantic_recall\"} 2"));
        assertTrue(prometheus.contains("clara_workflow_node_errors_total{category=\"EXTERNAL_SERVICE\",node_type=\"AGENT\",workflow=\"design_business_solution\"} 1"));
        assertTrue(prometheus.contains("clara_mcp_discovery_duration_millis_sum{server=\"codegraph\",status=\"OK\"} 430"));

        String report = metrics.report();
        assertTrue(report.contains("对话轮次 1"));
        assertTrue(report.contains("模型调用 1（错误 0）"));
        assertTrue(report.contains("首字 P95"));
        assertTrue(report.contains("压缩 1 次"));
        assertTrue(report.contains("召回命中 2 条"));
        assertTrue(report.contains("工作流节点 1（失败 1）"));
    }

    @Test
    public void countsFailoverAndOverflow() {
        TelemetryMetrics metrics = new TelemetryMetrics();
        metrics.onSpan(event("user.turn", TelemetrySpanKindEnum.TURN, TelemetryStatusEnum.OK, 100L,
                "failover.from", "deepseek", "failover.to", "kimi"));
        metrics.onSpan(event("context.overflow", TelemetrySpanKindEnum.OTHER, TelemetryStatusEnum.OK, 0L,
                "reason", "context_too_long"));

        String prometheus = metrics.prometheus();
        assertTrue(prometheus.contains("clara_failover_total{from=\"deepseek\",to=\"kimi\"} 1"));
        assertTrue(prometheus.contains("clara_context_overflow_total{reason=\"context_too_long\"} 1"));
    }

    @Test
    public void p95HandlesSamplesAboveLastBucket() {
        TelemetryMetrics metrics = new TelemetryMetrics();
        metrics.onSpan(event("user.turn", TelemetrySpanKindEnum.TURN, TelemetryStatusEnum.OK, 45_000L));

        assertTrue(metrics.report().contains("P95 耗时 45.0s"));
        assertTrue(metrics.prometheus().contains("clara_turn_duration_bucket{status=\"OK\",le=\"+Inf\"} 1"));
    }
    private static TelemetryEvent event(String name, TelemetrySpanKindEnum kind, TelemetryStatusEnum status,
            long duration, String... keyValues) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            attributes.put(keyValues[index], keyValues[index + 1]);
        }
        TelemetryEvent.Builder builder = TelemetryEvent.builder()
                .traceId("trace-" + name)
                .spanId("span-" + name)
                .name(name)
                .kind(kind)
                .startEpochMillis(1000L)
                .durationMillis(duration)
                .status(status)
                .attributes(attributes);
        if (status == TelemetryStatusEnum.ERROR) {
            builder.errorCategory(attributes.get("errorCategory")).errorCode("E");
        }
        return builder.build();
    }
}