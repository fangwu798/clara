package com.clara.mcp.client;

import com.clara.mcp.registry.ImmutableValues;
import java.util.Map;

/**
 * 表示。
 */
public final class JsonRpcError {
    private final int code;
    private final String message;
    private final Map<String, Object> data;

    public JsonRpcError(int code, String message, Map<String, Object> data) {
        if (message == null) { throw new IllegalArgumentException("message must not be null"); }
        this.code = code;
        this.message = message;
        this.data = ImmutableValues.copyMap(data);
    }
    public int getCode() { return code; }
    public String getMessage() { return message; }
    public Map<String, Object> getData() { return data; }
}
