package com.clara.core.transport;

import java.util.Locale;

/**
 * HTTP 媒体类型工具。
 */
public final class HttpMediaTypes {

    private static final String EVENT_STREAM = "text/event-stream";

    private HttpMediaTypes() {
    }

    /**
     * 判断 Content-Type 是否为 SSE 媒体类型，忽略参数与大小写。
     *
     * @param contentType 响应头 Content-Type，可为 null
     * @return 是 SSE 媒体类型时返回 true
     */
    public static boolean isEventStream(String contentType) {
        if (contentType == null) {
            return false;
        }
        int separator = contentType.indexOf(';');
        String mediaType = separator < 0 ? contentType : contentType.substring(0, separator);
        return EVENT_STREAM.equals(mediaType.trim().toLowerCase(Locale.ROOT));
    }
}
