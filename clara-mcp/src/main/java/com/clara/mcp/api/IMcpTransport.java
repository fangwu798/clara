package com.clara.mcp.api;

import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;
import com.clara.mcp.transport.jsonrpc.JsonRpcResponse;
import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

/**
 * MCP 传输抽象：屏蔽 stdio 与 streamable http 的差异，向上提供启动、请求 / 通知发送、取消与健康检查。
 *
 * <p>是什么、在哪一层：位于协议客户端（{@link IMcpClient}）与具体 IO 之间，只负责「一条 JSON-RPC 消息怎么发出去、
 * 怎么收回来」，不解释 MCP 方法语义。stdio 实现面向子进程管道，HTTP 实现面向 Streamable HTTP 端点。
 *
 * <p>生命周期：先 {@link #start(IMcpTransportListener)} 一次，再发送请求 / 通知，结束时 {@link #close()}。
 * 一个实例绑定一条连接；{@code start} 只能成功一次，重复调用抛 {@link IllegalStateException}，
 * 关闭后继续发送抛 {@code MCP_TRANSPORT_CLOSED}。
 *
 * <p>并发约定：同一实例可并发发送多个请求，实现按请求 id 配对响应；请求 id 由调用方保证唯一，
 * 重复 id 会返回失败或抛 {@code MCP_DUPLICATE_REQUEST_ID}。
 *
 * <p>备注：传输层只做字节收发与超时 / 取消，不做重试、不做能力校验；这些属于发现与网关的职责。
 * 关闭必须幂等，并先让所有在途请求失败，再释放流、进程或连接。
 */
public interface IMcpTransport extends Closeable {
    /**
     * 启动底层连接并注册监听。
     *
     * <p>监听回调在传输的读线程 / 事件线程上执行，实现不得阻塞；本方法只能成功调用一次。
     *
     * @param listener 传输事件监听，不可为 null
     */
    void start(IMcpTransportListener listener);

    /**
     * 异步发送一条 JSON-RPC 请求。
     *
     * <p>返回的 future 在收到同 id 响应、超时、取消或连接失败时完成；超时与取消时尽力向服务端发送
     * {@code notifications/cancelled}。超时由传输层负责，调用方不需要再包一层等待。
     *
     * @param request       请求消息，不可为 null；id 由调用方保证唯一
     * @param timeoutMillis 等待响应的超时毫秒数，必须为正
     * @return 响应 future，非 null
     */
    CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request, long timeoutMillis);

    /**
     * 单向发送一条 JSON-RPC 通知，不等待响应。
     *
     * <p>发送失败时，stdio 实现同步抛领域错误，HTTP 实现经 {@link IMcpTransportListener#onFailure(Throwable)}
     * 异步上报。
     *
     * @param notification 通知消息，不可为 null
     */
    void sendNotification(JsonRpcNotification notification);

    /**
     * 取消仍在途的请求。
     *
     * @param requestId 请求 id，不可为空白
     * @return 找到并终止了在途请求返回 true；请求不存在或已完成返回 false
     */
    boolean cancel(String requestId);

    /**
     * 快速探测连接是否可用。
     *
     * <p>stdio 检查子进程是否存活，HTTP 检查传输是否已启动且未关闭；不可用时抛领域错误。
     */
    void checkHealth();
}
