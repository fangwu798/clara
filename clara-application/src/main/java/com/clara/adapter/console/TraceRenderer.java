package com.clara.adapter.console;

import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetryStatusEnum;
import com.clara.utils.DurationUtils;
import com.clara.utils.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把链路事件渲染成可读的树：根是这一轮请求，子节点按发生顺序缩进展示。
 *
 * <p>为什么放在控制台适配层：事件模型属于 core，树形排版、耗时格式和属性筛选属于展示，不应污染核心契约。
 * 渲染只读事件，不访问文件或网络，便于单测。
 */
public final class TraceRenderer {

    private static final int NAME_WIDTH = 24;
    private static final int DURATION_WIDTH = 7;
    /** 一分钟对应的毫秒数：达到该值后耗时改用「x分y秒」，超过 60 分钟进位小时、超过 24 小时进位天。 */
    private static final long MINUTE_MILLIS = 60_000L;

    private static final Set<String> ATTRIBUTE_ORDER = new LinkedHashSet<>();

    static {
        Collections.addAll(ATTRIBUTE_ORDER, "provider", "model", "capability", "call", "mode", "steps",
                "messages", "tools", "history_messages", "window_messages", "prepared_messages",
                "recent_turns_kept", "items", "chars", "tool", "tool.route", "mcp.server", "round",
                "calls", "input_tokens", "output_tokens", "total_tokens", "max_tokens",
                "first_token_ms", "tokens", "stage", "evicted", "candidates", "above_threshold",
                "source", "failover.from", "failover.to", "failover.attempt",
                "workflow", "instance", "node", "node_type", "paused", "waiting_node",
                "server", "configs", "servers", "discovered", "failed", "resources", "prompts",
                "finish_reason", "status", "tool_roundtrips");
    }

    private TraceRenderer() {
    }

    /**
     * 渲染一组事件；事件应属于同一条 trace，顺序不作要求。
     *
     * @param events 事件列表，可为 null
     * @return 文本行，至少一行，非 null
     */
    public static List<String> lines(List<TelemetryEvent> events) {
        if (events == null || events.isEmpty()) {
            return Collections.singletonList("暂无链路记录。");
        }
        List<TelemetryEvent> sorted = new ArrayList<>(events);
        Collections.sort(sorted, (left, right) ->
                Long.compare(left.getStartEpochMillis(), right.getStartEpochMillis()));
        Map<String, List<TelemetryEvent>> children = children(sorted);
        Set<String> ids = ids(sorted);
        List<TelemetryEvent> roots = new ArrayList<>();
        for (TelemetryEvent event : sorted) {
            if (event.getParentSpanId() == null || !ids.contains(event.getParentSpanId())) {
                roots.add(event);
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add(header(sorted, roots));
        for (int index = 0; index < roots.size(); index++) {
            render(roots.get(index), "", index == roots.size() - 1, children, lines, 0);
        }
        return lines;
    }

    /**
     * 只渲染最后一个根及其子树，供单条 trace 的回看使用。
     *
     * @param events 事件列表，可为 null
     * @return 文本行，非 null
     */
    static List<String> linesOfLastRoot(List<TelemetryEvent> events) {
        if (events == null || events.isEmpty()) {
            return Collections.singletonList("暂无链路记录。");
        }
        List<TelemetryEvent> sorted = new ArrayList<>(events);
        Collections.sort(sorted, (left, right) ->
                Long.compare(left.getStartEpochMillis(), right.getStartEpochMillis()));
        Map<String, List<TelemetryEvent>> children = children(sorted);
        Set<String> ids = ids(sorted);
        TelemetryEvent root = null;
        for (TelemetryEvent event : sorted) {
            if (event.getParentSpanId() == null || !ids.contains(event.getParentSpanId())) {
                root = event;
            }
        }
        if (root == null) {
            return Collections.singletonList("暂无链路记录。");
        }
        List<String> lines = new ArrayList<>();
        lines.add(header(sorted, Collections.singletonList(root)));
        render(root, "", true, children, lines, 0);
        return lines;
    }

    private static void render(TelemetryEvent event, String prefix, boolean last,
            Map<String, List<TelemetryEvent>> children, List<String> lines, int depth) {
        String branch = depth == 0 ? "" : last ? "└─ " : "├─ ";
        lines.add(prefix + branch + padRight(event.getName(), NAME_WIDTH)
                + padLeft(formatDuration(event.getDurationMillis()), DURATION_WIDTH)
                + "  " + status(event) + attributes(event) + error(event));
        List<TelemetryEvent> descendants = children.get(event.getSpanId());
        if (descendants == null || descendants.isEmpty()) {
            return;
        }
        String childPrefix = prefix + (depth == 0 ? "" : last ? "   " : "│  ");
        for (int index = 0; index < descendants.size(); index++) {
            render(descendants.get(index), childPrefix, index == descendants.size() - 1, children, lines,
                    depth + 1);
        }
    }

    private static String header(List<TelemetryEvent> events, List<TelemetryEvent> roots) {
        long start = Long.MAX_VALUE;
        long end = 0L;
        boolean failed = false;
        boolean cancelled = false;
        for (TelemetryEvent event : events) {
            start = Math.min(start, event.getStartEpochMillis());
            end = Math.max(end, event.getStartEpochMillis() + event.getDurationMillis());
            failed |= event.getStatus() == TelemetryStatusEnum.ERROR;
            cancelled |= event.getStatus() == TelemetryStatusEnum.CANCELLED;
        }
        String status = failed ? "ERROR" : cancelled ? "CANCELLED" : "OK";
        long duration = start == Long.MAX_VALUE ? 0L : Math.max(0L, end - start);
        String traceId = roots.isEmpty() ? events.get(0).getTraceId() : roots.get(0).getTraceId();
        return "链路 " + traceId + "  阶段 " + events.size() + " 个  总耗时 "
                + formatDuration(duration) + "  状态 " + status;
    }

    private static Map<String, List<TelemetryEvent>> children(List<TelemetryEvent> events) {
        Map<String, List<TelemetryEvent>> children = new HashMap<>();
        for (TelemetryEvent event : events) {
            String parent = event.getParentSpanId();
            if (parent == null || parent.trim().isEmpty()) {
                continue;
            }
            List<TelemetryEvent> list = children.computeIfAbsent(parent, key -> new ArrayList<>());
            list.add(event);
        }
        return children;
    }

    private static Set<String> ids(List<TelemetryEvent> events) {
        Set<String> ids = new LinkedHashSet<>();
        for (TelemetryEvent event : events) {
            ids.add(event.getSpanId());
        }
        return ids;
    }

    private static String status(TelemetryEvent event) {
        TelemetryStatusEnum status = event.getStatus();
        return status == null ? "OK" : status.name();
    }

    private static String attributes(TelemetryEvent event) {
        StringBuilder text = new StringBuilder();
        for (String key : ATTRIBUTE_ORDER) {
            String value = event.getAttributes().get(key);
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            text.append(text.length() == 0 ? "  " : " ").append(key).append('=').append(shorten(value, 48));
        }
        return text.toString();
    }

    private static String error(TelemetryEvent event) {
        if (event.getStatus() != TelemetryStatusEnum.ERROR) {
            return "";
        }
        StringBuilder text = new StringBuilder("  error=");
        String category = event.getErrorCategory() == null ? "-" : event.getErrorCategory();
        String code = event.getErrorCode() == null ? "-" : event.getErrorCode();
        text.append(category).append('/').append(code);
        if (event.getErrorMessage() != null && !event.getErrorMessage().trim().isEmpty()) {
            text.append(" ").append(shorten(event.getErrorMessage(), 96));
        }
        return text.toString();
    }

    private static String formatDuration(long millis) {
        if (millis >= MINUTE_MILLIS) {
            return DurationUtils.formatMillis(millis);
        }
        if (millis < 1000L) {
            return millis + "ms";
        }
        return String.format(java.util.Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    private static String padRight(String text, int width) {
        String value = text == null ? "" : text;
        if (value.length() >= width) {
            return value + " ";
        }
        StringBuilder builder = new StringBuilder(value);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
    }

    private static String padLeft(String text, int width) {
        String value = text == null ? "" : text;
        StringBuilder builder = new StringBuilder();
        for (int index = StringUtils.displayWidth(value); index < width; index++) {
            builder.append(' ');
        }
        return builder.append(value).toString();
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
