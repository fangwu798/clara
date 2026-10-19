package com.clara.core.transport;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 请求头校验与冻结工具。
 */
public final class HttpHeaders {

    private static final String HEADER_NAME = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";
    private static final Set<String> TRANSPORT_CONTROLLED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("host", "content-length", "connection", "transfer-encoding")));

    private HttpHeaders() {
    }

    /**
     * 校验请求头并返回不可修改的有序副本。
     *
     * @param headers 请求头，可为 null
     * @return 不可修改映射，非 null
     * @throws IllegalArgumentException 头名或头值非法、同名头（忽略大小写）重复、
     *                                  或占用传输层管理的头时
     */
    public static Map<String, String> validate(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, String> safe = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || !name.matches(HEADER_NAME)) {
                throw new IllegalArgumentException("invalid header name");
            }
            if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("invalid header value");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (!names.add(lower)) {
                throw new IllegalArgumentException("duplicate header name");
            }
            if (TRANSPORT_CONTROLLED.contains(lower)) {
                throw new IllegalArgumentException("transport-controlled header");
            }
            safe.put(name, value);
        }
        return Collections.unmodifiableMap(safe);
    }

    /**
     * 读取响应头中的第一个值，头名忽略大小写。
     *
     * @param headers 响应头，可为 null
     * @param name 头名，可为 null
     * @return 第一个值；头不存在或没有值时返回 null
     */
    public static String firstValue(Map<String, List<String>> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())
                    && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }
}
