package com.clara.model.provider.http;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 封装服务提供商HTTP操作的输入参数。
 */
public final class ProviderHttpRequest {
    private final String method;
    private final URI endpoint;
    private final Map<String, String> headers;
    private final byte[] body;

    public ProviderHttpRequest(String method, URI endpoint, Map<String, String> headers, byte[] body) {
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        if (!"GET".equals(normalizedMethod) && !"POST".equals(normalizedMethod) && !"DELETE".equals(normalizedMethod)) {
            throw new IllegalArgumentException("unsupported HTTP method");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        byte[] safeBody = body == null ? new byte[0] : body;
        if (("GET".equals(normalizedMethod) || "DELETE".equals(normalizedMethod)) && safeBody.length != 0) {
            throw new IllegalArgumentException("method must not contain a body");
        }
        LinkedHashMap<String, String> safeHeaders = new LinkedHashMap<String, String>();
        Set<String> headerNames = new HashSet<String>();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requireHeader(entry.getKey(), entry.getValue());
                String lower = entry.getKey().toLowerCase(Locale.ROOT);

                if (!headerNames.add(lower)) {
                    throw new IllegalArgumentException("duplicate header name");
                }
                if ("host".equals(lower) || "content-length".equals(lower) || "connection".equals(lower) || "transfer-encoding".equals(lower)) {
                    throw new IllegalArgumentException("transport-controlled header");
                }

                safeHeaders.put(entry.getKey(), entry.getValue());
            }
        }
        this.method = normalizedMethod;
        this.endpoint = endpoint;
        this.headers = Collections.unmodifiableMap(safeHeaders);
        this.body = safeBody.clone();
    }

    public String getMethod() {
        return method;
    }

    public URI getEndpoint() {
        return endpoint;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public byte[] getBody() {
        return body.clone();
    }

    private static void requireHeader(String name, String value) {
        if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new IllegalArgumentException("invalid header name");
        }
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid header value");
        }
    }
}
