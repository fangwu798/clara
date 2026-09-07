package com.clara.mcp.client;

import com.clara.mcp.registry.ImmutableValues;
import java.util.Map;

/**
 * 封装JSONRPC操作的响应数据。
 */
public final class JsonRpcResponse {
    private final String id;
    private final Map<String, Object> result;
    private final JsonRpcError error;

    public JsonRpcResponse(String id, Map<String, Object> result, JsonRpcError error) {
        if (id == null || id.trim().isEmpty()) { throw new IllegalArgumentException("id must not be blank"); }
        if ((result == null) == (error == null)) {
            throw new IllegalArgumentException("exactly one of result or error is required");
        }
        this.id = id;
        this.result = result == null ? null : ImmutableValues.copyMap(result);
        this.error = error;
    }
    public String getJsonRpc() { return "2.0"; }
    public String getId() { return id; }
    public Map<String, Object> getResult() { return result; }
    public JsonRpcError getError() { return error; }
}
