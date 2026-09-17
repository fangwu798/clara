package com.clara.model.provider.http.impl;

import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 封装服务提供商HTTP操作的响应数据。
 */
@Data
public final class ProviderHttpResponse {
    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    public ProviderHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("invalid HTTP status code");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        LinkedHashMap<String, List<String>> copied = new LinkedHashMap<String, List<String>>();
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().contains(null)) {
                    continue;
                }
                copied.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }
        }
        this.statusCode = statusCode;
        this.headers = Collections.unmodifiableMap(copied);
        this.body = body.clone();
    }


    public byte[] getBody() {
        return body.clone();
    }
}
