package com.clara.adapter.console;

import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.core.telemetry.TelemetryStatusEnum;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link TraceRenderer} 的补充用例：只渲染最后一棵根树、孤儿节点、状态与截断边界。
 *
 * <p>{@code /trace} 只关心「最近一轮」，多根树必须只取最后一个根；
 * 同时超长属性与错误信息要截断，避免刷屏打乱控制台表格。
 */
public class TraceRendererEdgeTest {

    @Test
    public void emptyInputsProduceHint() {
        assertEquals("暂无链路记录。", TraceRenderer.linesOfLastRoot(null).get(0));
        assertEquals("暂无链路记录。", TraceRenderer.linesOfLastRoot(Collections.emptyList()).get(0));
    }

    @Test
    public void rendersOnlyTheLastRoot() {
        List<TelemetryEvent> events = Arrays.asList(
                event("t1", "s1", null, "first.turn", 0L, 10L, TelemetryStatusEnum.OK),
                event("t1", "s2", null, "second.turn", 100L, 20L, TelemetryStatusEnum.OK),
                event("t1", "s3", "s2", "model.call", 110L, 30L, TelemetryStatusEnum.OK));

        List<String> lines = TraceRenderer.linesOfLastRoot(events);

        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("链路 t1"));
        assertTrue(lines.get(1).contains("second.turn"));
        assertTrue(lines.get(2).contains("model.call"));
    }

    @Test
    public void orphanNodeBecomesRoot() {
        List<TelemetryEvent> events = Collections.singletonList(
                event("t1", "s1", "missing-parent", "orphan.call", 0L, 5L, TelemetryStatusEnum.OK));

        List<String> lines = TraceRenderer.linesOfLastRoot(events);

        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("orphan.call"));
    }

    @Test
    public void formatsSecondsAndCancelledHeader() {
        List<TelemetryEvent> events = Arrays.asList(
                event("t1", "s1", null, "turn", 0L, 1200L, TelemetryStatusEnum.CANCELLED),
                event("t1", "s2", "s1", "child", 100L, 1500L, TelemetryStatusEnum.OK));

        List<String> lines = TraceRenderer.lines(events);

        assertTrue(lines.get(0).contains("CANCELLED"));
        assertTrue(lines.get(0).contains("1.6s"));
        assertTrue(lines.get(1).contains("1.2s"));
        assertTrue(lines.get(2).contains("1.5s"));
    }

    @Test
    public void errorWithoutDetailsUsesDashes() {
        TelemetryEvent root = TelemetryEvent.builder()
                .traceId("t1")
                .spanId("s1")
                .name("model.call")
                .kind(TelemetrySpanKindEnum.MODEL)
                .startEpochMillis(1L)
                .durationMillis(2L)
                .status(TelemetryStatusEnum.ERROR)
                .build();

        List<String> lines = TraceRenderer.lines(Collections.singletonList(root));

        assertTrue(lines.get(1).contains("error=-/-"));
    }

    @Test
    public void unknownStatusFallsBackToOk() {
        TelemetryEvent root = TelemetryEvent.builder()
                .traceId("t1")
                .spanId("s1")
                .name("model.call")
                .kind(TelemetrySpanKindEnum.MODEL)
                .startEpochMillis(1L)
                .durationMillis(2L)
                .status(null)
                .build();

        List<String> lines = TraceRenderer.lines(Collections.singletonList(root));

        assertTrue(lines.get(0).contains("状态 OK"));
        assertTrue(lines.get(1).contains(" OK"));
    }

    @Test
    public void longAttributesAndNamesAreShortened() {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < 60; index++) {
            value.append('x');
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("provider", value.toString());
        attributes.put("unknown_key", "ignored");
        attributes.put("model", "   ");

        TelemetryEvent root = TelemetryEvent.builder()
                .traceId("t1")
                .spanId("s1")
                .name("a-very-long-span-name-that-exceeds-width")
                .kind(TelemetrySpanKindEnum.MODEL)
                .startEpochMillis(1L)
                .durationMillis(2L)
                .status(TelemetryStatusEnum.OK)
                .attributes(attributes)
                .build();

        List<String> lines = TraceRenderer.lines(Collections.singletonList(root));

        assertTrue(lines.get(1).contains("provider=xxxxxxxx"));
        assertTrue(lines.get(1).contains("..."));
        assertTrue(!lines.get(1).contains("unknown_key"));
        assertTrue(lines.get(1).contains("a-very-long-span-name-that-exceeds-width "));
    }

    @Test
    public void nestedChildrenUseIndentedPrefix() {
        List<TelemetryEvent> events = Arrays.asList(
                event("t1", "s1", null, "root", 0L, 100L, TelemetryStatusEnum.OK),
                event("t1", "s2", "s1", "child", 10L, 20L, TelemetryStatusEnum.OK),
                event("t1", "s3", "s2", "grandchild", 20L, 5L, TelemetryStatusEnum.OK));

        List<String> lines = TraceRenderer.lines(events);

        assertEquals(4, lines.size());
        assertTrue(lines.get(3).contains("grandchild"));
    }

    private static TelemetryEvent event(String traceId, String spanId, String parent, String name,
            long start, long duration, TelemetryStatusEnum status) {
        return TelemetryEvent.builder()
                .traceId(traceId)
                .spanId(spanId)
                .parentSpanId(parent)
                .name(name)
                .kind(TelemetrySpanKindEnum.CONVERSATION)
                .startEpochMillis(start)
                .durationMillis(duration)
                .status(status)
                .build();
    }
}