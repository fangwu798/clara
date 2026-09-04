package com.clara.mcp.client;

import com.clara.mcp.registry.ImmutableValues;
import java.util.Map;

public final class JsonRpcNotification {
    private final String method;
    private final Map<String, Object> params;
    public JsonRpcNotification(String method, Map<String, Object> params) {
        if (method == null || method.trim().isEmpty()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        this.method = method; this.params = ImmutableValues.copyMap(params);
    }
    public String getJsonRpc() { return "2.0"; }
    public String getMethod() { return method; }
    public Map<String, Object> getParams() { return params; }
}
