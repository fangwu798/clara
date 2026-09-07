package com.clara.mcp.transport.http;

/**
 * 将SSE事件数据解析为领域对象。
 */
final class SseEventParser {
    private final int maxEventChars;
    private final StringBuilder data = new StringBuilder();
    private String id;
    private Long retryMillis;
    SseEventParser(int maxEventChars) {
        if (maxEventChars <= 0) { throw new IllegalArgumentException("maxEventChars must be positive"); }
        this.maxEventChars = maxEventChars;
    }
    SseEvent accept(String line) {
        if (line == null || line.isEmpty()) return dispatch();
        if (line.charAt(0) == ':') return null;
        int separator = line.indexOf(':');
        String field = separator < 0 ? line : line.substring(0, separator);
        String value = separator < 0 ? "" : line.substring(separator + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        if ("data".equals(field)) {
            if (data.length() > 0) data.append('\n');
            data.append(value);
            if (data.length() > maxEventChars) { throw new SseEventTooLargeException(); }
        } else if ("id".equals(field) && value.indexOf('\u0000') < 0) {
            id = value;
        } else if ("retry".equals(field)) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed >= 0L) retryMillis = parsed;
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }
    private SseEvent dispatch() {
        if (data.length() == 0 && id == null && retryMillis == null) return null;
        SseEvent event = new SseEvent(data.toString(), id, retryMillis);
        data.setLength(0); id = null; retryMillis = null;
        return event;
    }
}

/**
 * 表示处理SSE事件过大时发生的异常。
 */
final class SseEventTooLargeException extends IllegalArgumentException {
    SseEventTooLargeException() { super("SSE event is too large"); }
}
