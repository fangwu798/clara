package com.clara.mcp.transport.jsonrpc;

import com.clara.utils.StringUtils;
import com.clara.mcp.registry.ImmutableValues;
import java.util.Map;

/**
 * 封装JSONRPC操作的输入参数。
 */
public final class JsonRpcRequest {
    private final String id;
    private final String method;
    private final Map<String, Object> params;

    public JsonRpcRequest(String id, String method, Map<String, Object> params) {
        if (StringUtils.isBlank(id)) { throw new IllegalArgumentException("id must not be blank"); }
        if (StringUtils.isBlank(method)) { throw new IllegalArgumentException("method must not be blank"); }
        this.id = id;
        this.method = method;
        this.params = ImmutableValues.copyMap(params);
    }
    @SuppressWarnings("java:S3400")
    public String getJsonRpc() { return "2.0"; }
    public String getId() { return id; }
    public String getMethod() { return method; }
    public Map<String, Object> getParams() { return params; }
}
