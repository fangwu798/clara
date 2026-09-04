package com.clara.mcp.client;

import com.clara.mcp.registry.ImmutableValues;
import java.util.Map;

public final class JsonRpcRequest {
    private final String id;
    private final String method;
    private final Map<String, Object> params;

    public JsonRpcRequest(String id, String method, Map<String, Object> params) {
        if (isBlank(id)) throw new IllegalArgumentException("id must not be blank");
        if (isBlank(method)) throw new IllegalArgumentException("method must not be blank");
        this.id = id;
        this.method = method;
        this.params = ImmutableValues.copyMap(params);
    }
    public String getJsonRpc() { return "2.0"; }
    public String getId() { return id; }
    public String getMethod() { return method; }
    public Map<String, Object> getParams() { return params; }
    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
