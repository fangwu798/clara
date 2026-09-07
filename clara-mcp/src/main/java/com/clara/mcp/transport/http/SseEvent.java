package com.clara.mcp.transport.http;

/**
 * 表示SSE产生的事件。
 */
final class SseEvent {
    private final String data;
    private final String id;
    private final Long retryMillis;
    SseEvent(String data, String id, Long retryMillis) {
        this.data = data; this.id = id; this.retryMillis = retryMillis;
    }
    String getData() { return data; }
    String getId() { return id; }
    Long getRetryMillis() { return retryMillis; }
}
