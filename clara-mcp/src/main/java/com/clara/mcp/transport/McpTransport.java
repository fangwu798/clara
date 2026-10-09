package com.clara.mcp.transport;

import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;
import com.clara.mcp.transport.jsonrpc.JsonRpcResponse;
import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

/**
 * MCP 传输抽象：屏蔽 stdio 与 streamable http 的差异，向上提供启动、请求 / 通知发送、取消与健康检查。
 *
 * <p>实现约定：{@link #start(McpTransportListener)} 启动传输并注册监听；{@link #sendRequest} 异步返回响应；
 * {@link #sendNotification} 单向发送；{@link #cancel(String)} 按请求 id 取消；{@link #checkHealth()} 探测可用性。
 * {@code extends Closeable}，{@code close()} 需幂等并释放底层资源。
 */
public interface McpTransport extends Closeable {
    void start(McpTransportListener listener);
    CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request, long timeoutMillis);
    void sendNotification(JsonRpcNotification notification);
    boolean cancel(String requestId);
    void checkHealth();
}
