package com.clara.mcp.transport;

import com.clara.mcp.client.JsonRpcNotification;
import com.clara.mcp.client.JsonRpcRequest;

public interface McpTransportListener {
    void onNotification(JsonRpcNotification notification);
    void onServerRequest(JsonRpcRequest request);
    void onFailure(Throwable failure);
}
