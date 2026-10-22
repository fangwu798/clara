package com.clara.adapter.console;

import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.core.telemetry.TelemetryStatusEnum;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link TraceRenderer} 的树形排版与错误展示测试。
 */
public class TraceRendererTest {

    @Test
    public void rendersTreeWithDurationsAndAttributes() {
        List<TelemetryEvent> events = Arrays.asList(
                event("t1", "s1", null, "conversation.turn", 0L, 1200L, TelemetryStatusEnum.OK,
                        "provider", "DEEPSEEK"),
                event("t1", "s2", "s1", "model.call", 100L, 800L, TelemetryStatusEnum.OK,
                        "input_tokens", "12"),
                event("t1", "s3", "s1", "tool.call", 950L, 200L, TelemetryStatusEnum.OK,
                        "tool", "code_graph"));

        List<String> lines = TraceRenderer.lines(events);

        assertEquals(4, lines.size());
        assertTrue(lines.get(0).contains("链路 t1"));
        assertTrue(lines.get(1).contains("conversation.turn"));
        assertTrue(lines.get(1).contains("provider=DEEPSEEK"));
        assertTrue(lines.get(2).contains("├─ model.call"));
        assertTrue(lines.get(2).contains("800ms"));
        assertTrue(lines.get(3).contains("└─ tool.call"));
        assertTrue(lines.get(3).contains("tool=code_graph"));
    }

    @Test
    public void rendersErrorCategoryAndCode() {
        TelemetryEvent root = TelemetryEvent.builder()
                .traceId("t2")
                .spanId("s1")
                .name("model.call")
                .kind(TelemetrySpanKindEnum.MODEL)
                .startEpochMillis(1L)
                .durationMillis(50L)
                .status(TelemetryStatusEnum.ERROR)
                .errorCategory("EXTERNAL_SERVICE")
                .errorCode("MODEL_PROVIDER_FAILED")
                .errorMessage("平台返回 503")
                .build();

        List<String> lines = TraceRenderer.lines(Collections.singletonList(root));

        assertTrue(lines.get(0).contains("状态 ERROR"));
        assertTrue(lines.get(1).contains("ERROR"));
        assertTrue(lines.get(1).contains("EXTERNAL_SERVICE/MODEL_PROVIDER_FAILED"));
        assertTrue(lines.get(1).contains("平台返回 503"));
    }

    @Test
    public void emptyEventsProduceHint() {
        assertEquals("暂无链路记录。", TraceRenderer.lines(Collections.emptyList()).get(0));
    }

    private static TelemetryEvent event(String traceId, String spanId, String parent, String name,
            long start, long duration, TelemetryStatusEnum status, String key, String value) {
        return TelemetryEvent.builder()
                .traceId(traceId)
                .spanId(spanId)
                .parentSpanId(parent)
                .name(name)
                .kind(TelemetrySpanKindEnum.CONVERSATION)
                .startEpochMillis(start)
                .durationMillis(duration)
                .status(status)
                .attributes(Collections.singletonMap(key, value))
                .build();
    }
}
