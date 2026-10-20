package com.clara.mcp.transport.jsonrpc;

import com.clara.mcp.registry.ImmutableValues;
import lombok.Getter;

import java.util.Map;

/**
 * 表示。
 */
@Getter
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
}
