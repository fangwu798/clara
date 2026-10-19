package com.clara.mcp.api;

import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;

/**
 * MCP 传输事件监听：接收服务端主动发来的通知、服务端发起的请求，以及传输层自身故障。
 *
 * <p>调用线程：回调由传输实现的读线程 / 事件线程触发，可能连续多次；实现应快速返回，
 * 不要在其中做阻塞 IO 或长时间计算。
 *
 * <p>备注：监听器只负责「事件到了」，不负责响应。当前客户端实现忽略服务端请求（{@code onServerRequest} 为空实现）。
 * 回调抛出的异常会被传输层捕获并转成 {@link #onFailure(Throwable)}，不会中断读循环。
 */
public interface IMcpTransportListener {
    /**
     * 收到服务端发来的单向通知（无 id）。
     *
     * @param notification 通知消息，非 null
     */
    void onNotification(JsonRpcNotification notification);

    /**
     * 收到服务端发起的请求（带 id）。
     *
     * @param request 服务端请求，非 null
     */
    void onServerRequest(JsonRpcRequest request);

    /**
     * 传输层故障：解析失败、读循环异常、异步发送失败等。
     *
     * <p>同一故障可能被多次上报，实现应做幂等处理。
     *
     * @param failure 故障原因，非 null
     */
    void onFailure(Throwable failure);
}
