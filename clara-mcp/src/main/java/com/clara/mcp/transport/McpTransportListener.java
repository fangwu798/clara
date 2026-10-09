package com.clara.mcp.transport;

import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;

/**
 * 监听MCP传输器的生命周期事件。
 */
public interface McpTransportListener {
    void onNotification(JsonRpcNotification notification);
    void onServerRequest(JsonRpcRequest request);
    void onFailure(Throwable failure);
}
