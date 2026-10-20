package com.clara.core.transport.sse;

import lombok.Getter;

/**
 * 一次 Server-Sent Events 事件的不可变值对象。
 */
@Getter
public final class SseEvent {

    private final String event;
    private final String data;
    private final String id;
    private final Long retryMillis;

    /**
     * 创建 SSE 事件。
     *
     * @param event       事件名，非 null；未声明时由解析器填默认值 {@code message}
     * @param data        事件数据，可为 null
     * @param id          事件标识，可为 null
     * @param retryMillis 重连间隔毫秒数，可为 null
     */
    public SseEvent(String event, String data, String id, Long retryMillis) {
        this.event = event;
        this.data = data;
        this.id = id;
        this.retryMillis = retryMillis;
    }

}
