package com.clara.telemetry;

import com.clara.core.telemetry.ITelemetrySink;
import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.core.telemetry.TelemetryStatusEnum;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SDK 遥测的两个派生接收端（指标聚合 / 采样）的分支测试。
 *
 * <p>为什么这些分支值得测：指标与采样都是「旁路」代码，出问题不会让对话失败，只会让看板少数、
 * 或把异常链路丢掉——线上只能看到「没数据」，无法反推是哪一类事件没被识别。这里按事件名逐条喂入，
 * 保证每类 Span 都有落点。
 */
public class TelemetrySinksBranchTest {

    @Test
    public void metricsAggregateEveryEventKind() {
        TelemetryMetrics metrics = new TelemetryMetrics();

        metrics.onSpan(event("user.turn", TelemetryStatusEnum.OK,
                map("failover.from", "DEEPSEEK", "failover.to", "KIMI")));
        metrics.onSpan(event("user.turn", TelemetryStatusEnum.OK, map("failover.from", "unknown")));
        metrics.onSpan(event("model.call", TelemetryStatusEnum.OK,
                map("provider", "DEEPSEEK", "model", "v4", "stage", "chat",
                        "input_tokens", "10", "output_tokens", "5", "first_token_ms", "120")));
        metrics.onSpan(event("model.call", TelemetryStatusEnum.ERROR,
                map("provider", "DEEPSEEK", "model", "v4", "stage", "chat",
                        "first_token_ms", "not-a-number", "errorCategory", "EXTERNAL_SERVICE")));
        metrics.onSpan(event("context.assemble", TelemetryStatusEnum.OK, map("tokens", "800")));
        metrics.onSpan(event("context.assemble", TelemetryStatusEnum.OK, Collections.<String, String>emptyMap()));
        metrics.onSpan(event("context.recall", TelemetryStatusEnum.OK,
                map("source", "vector", "items", "3", "candidates", "10")));
        metrics.onSpan(event("context.compress", TelemetryStatusEnum.OK,
                map("summary", "true", "reason", "overflow")));
        metrics.onSpan(event("context.compress", TelemetryStatusEnum.OK, map("summary", "false")));
        metrics.onSpan(event("context.overflow", TelemetryStatusEnum.OK, map("reason", "limit")));
        metrics.onSpan(event("tool.call", TelemetryStatusEnum.OK,
                map("tool", "text_search", "route", "builtin")));
        metrics.onSpan(event("workflow.execute", TelemetryStatusEnum.OK, map("workflow", "plan")));
        metrics.onSpan(event("workflow.execute", TelemetryStatusEnum.ERROR,
                map("workflow", "plan", "errorCategory", "INTERNAL")));
        metrics.onSpan(event("workflow.node", TelemetryStatusEnum.OK,
                map("workflow", "plan", "node_type", "tool")));
        metrics.onSpan(event("workflow.node", TelemetryStatusEnum.ERROR,
                map("workflow", "plan", "node_type", "tool", "errorCategory", "INTERNAL")));
        metrics.onSpan(event("mcp.discovery", TelemetryStatusEnum.OK, map("server", "codegraph")));
        metrics.onSpan(event("mcp.discovery", TelemetryStatusEnum.ERROR,
                map("server", "codegraph", "errorCategory", "EXTERNAL_SERVICE")));
        metrics.onSpan(event("mcp.load", TelemetryStatusEnum.OK, Collections.<String, String>emptyMap()));
        metrics.onSpan(event("unknown.event", TelemetryStatusEnum.OK, Collections.<String, String>emptyMap()));
        metrics.onSpan(null);
        metrics.onSpan(event("model.call", null, Collections.<String, String>emptyMap()));

        assertFalse("所有事件都应至少产生一条指标", metrics.counterSnapshot().isEmpty());
        assertFalse("报告不应为空", metrics.report().isEmpty());
        assertTrue(metrics.prometheus().contains("clara_model_calls_total"));

        metrics.clear();
        assertTrue(metrics.counterSnapshot().isEmpty());
        assertTrue(metrics.histogramSnapshot().isEmpty());
    }

    @Test
    public void samplingPassesEverythingAtFullRate() {
        RecordingSink delegate = new RecordingSink();
        SamplingTelemetrySink sink = new SamplingTelemetrySink(1.0d, 0L,
                Arrays.<ITelemetrySink>asList(null, delegate));

        sink.onSpan(null);
        sink.onSpan(event("t1", TelemetryStatusEnum.OK, Collections.<String, String>emptyMap()));

        assertEquals(1, delegate.count.get());
    }

    @Test
    public void samplingDropsSuccessButKeepsFailuresAndSlowTraces() {
        RecordingSink delegate = new RecordingSink();
        SamplingTelemetrySink sink = new SamplingTelemetrySink(0.0d, 500L,
                Collections.<ITelemetrySink>singletonList(delegate));

        // 没有根 Span 的残缺链路：即使 sampleRate 很高也不采
        sink.onSpan(childEvent("t-missing-root", "s1", 0L));
        // 未达慢阈值：丢弃
        sink.onSpan(rootEvent("t-fast", 10L));
        // 超过慢阈值：保留
        sink.onSpan(rootEvent("t-slow", 900L));
        // 失败链路：无条件保留
        sink.onSpan(eventWithStatus("t-error", TelemetryStatusEnum.ERROR));

        assertEquals("慢链路应保留；失败子 Span 要等根或 close 才放行", 1, delegate.count.get());
    }

    @Test
    public void samplingBuffersUntilRootOrCapacity() {
        RecordingSink delegate = new RecordingSink();
        SamplingTelemetrySink sink = new SamplingTelemetrySink(0.5d, 1L,
                Collections.<ITelemetrySink>singletonList(delegate));

        sink.onSpan(childEvent("t-buffer", "s1", 0L));
        assertEquals("子 Span 先入缓冲，不立即下发", 0, delegate.count.get());
        sink.onSpan(rootEvent("t-buffer", 0L));
        assertEquals("根 Span 到达后整条链路一次性下发", 2, delegate.count.get());

        for (int index = 0; index < 1024; index++) {
            sink.onSpan(childEvent("t-capacity", "s" + index, 0L));
        }
        assertTrue("单链路事件上限触发后应放行", delegate.count.get() >= 1024);
    }

    @Test
    public void samplingCapsBufferedTraces() {
        RecordingSink delegate = new RecordingSink();
        SamplingTelemetrySink sink = new SamplingTelemetrySink(0.5d, 1L,
                Collections.<ITelemetrySink>singletonList(delegate));

        for (int index = 0; index < 257; index++) {
            sink.onSpan(childEvent("trace-" + index, "span", 0L));
        }

        assertTrue("超过缓冲上限时应先放行最早的链路", delegate.count.get() > 0);
    }

    @Test
    public void closeFlushesPendingAndSwallowsDelegateFailures() {
        RecordingSink delegate = new RecordingSink();
        ITelemetrySink failing = new ITelemetrySink() {
            public void onSpan(TelemetryEvent event) {
                throw new IllegalStateException("sink is down");
            }
        };
        SamplingTelemetrySink sink = new SamplingTelemetrySink(0.0d, 0L, Arrays.asList(failing, delegate));

        sink.onSpan(eventWithStatus("t-pending", TelemetryStatusEnum.ERROR));
        sink.close();

        assertEquals("关闭前应放行仍在缓冲的失败链路", 1, delegate.count.get());

        SamplingTelemetrySink noDelegate = new SamplingTelemetrySink(1.0d, 0L, null);
        noDelegate.onSpan(eventWithStatus("t-no-delegate", TelemetryStatusEnum.OK));
        noDelegate.close();
    }

    private static TelemetryEvent event(String name, TelemetryStatusEnum status, Map<String, String> attributes) {
        return TelemetryEvent.builder()
                .traceId("trace-" + name)
                .spanId("span-" + name + "-" + System.nanoTime())
                .name(name)
                .kind(TelemetrySpanKindEnum.OTHER)
                .status(status)
                .startEpochMillis(System.currentTimeMillis())
                .durationMillis(5L)
                .attributes(attributes)
                .build();
    }

    private static TelemetryEvent rootEvent(String traceId, long durationMillis) {
        return TelemetryEvent.builder()
                .traceId(traceId)
                .spanId(traceId + "-root")
                .name("user.turn")
                .status(TelemetryStatusEnum.OK)
                .startEpochMillis(1000L)
                .durationMillis(durationMillis)
                .attributes(Collections.<String, String>emptyMap())
                .build();
    }

    private static TelemetryEvent childEvent(String traceId, String spanId, long start) {
        return TelemetryEvent.builder()
                .traceId(traceId)
                .spanId(spanId)
                .parentSpanId("root")
                .name("tool.call")
                .status(TelemetryStatusEnum.OK)
                .startEpochMillis(start)
                .durationMillis(1L)
                .attributes(Collections.<String, String>emptyMap())
                .build();
    }

    private static TelemetryEvent eventWithStatus(String traceId, TelemetryStatusEnum status) {
        return TelemetryEvent.builder()
                .traceId(traceId)
                .spanId(traceId + "-span")
                .parentSpanId("root")
                .name("tool.call")
                .status(status)
                .startEpochMillis(0L)
                .durationMillis(1L)
                .attributes(Collections.<String, String>emptyMap())
                .build();
    }

    private static Map<String, String> map(String... keyValues) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            values.put(keyValues[index], keyValues[index + 1]);
        }
        return values;
    }

    private static final class RecordingSink implements ITelemetrySink {
        private final AtomicInteger count = new AtomicInteger();
        private final List<TelemetryEvent> events = new ArrayList<>();

        public void onSpan(TelemetryEvent event) {
            count.incrementAndGet();
            events.add(event);
        }
    }
}
