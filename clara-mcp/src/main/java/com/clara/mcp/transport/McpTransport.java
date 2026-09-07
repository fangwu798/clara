package com.clara.mcp.transport;

import com.clara.mcp.client.JsonRpcNotification;
import com.clara.mcp.client.JsonRpcRequest;
import com.clara.mcp.client.JsonRpcResponse;
import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

/**
 * 传输MCP消息。
 */
public interface McpTransport extends Closeable {
    void start(McpTransportListener listener);
    CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request, long timeoutMillis);
    void sendNotification(JsonRpcNotification notification);
    boolean cancel(String requestId);
    void checkHealth();
}
