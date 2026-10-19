package com.clara.core.transport.sse;

import java.util.List;
import java.util.Map;

/**
 * SSE 事件监听器。
 */
public interface ISseEventListener {

    /**
     * 响应头到达、开始读取事件流时回调。
     *
     * @param status  HTTP 状态码
     * @param headers 响应头，非 null
     */
    default void onStart(int status, Map<String, List<String>> headers) {
    }

    /**
     * 收到一个完整 SSE 事件。
     *
     * @param event 事件对象，非 null
     * @return 继续解析返回 true；提前停止返回 false
     */
    boolean onEvent(SseEvent event);
}
