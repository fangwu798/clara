package com.clara.mcp.transport;

import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;

/**
 * MCP JSON 编解码：在 JSON-RPC 消息对象与线格式 JSON 文本之间互转，协议编解码集中于此。
 *
 * <p>实现约定：{@link #decode(String)} 把文本解析为响应、通知或服务端请求；非法 JSON 或结构不符抛
 * {@code MCP_INVALID_MESSAGE} 领域错误。
 */
public interface McpJsonCodec {
    String encode(JsonRpcRequest request);
    String encode(JsonRpcNotification notification);
    McpDecodedMessage decode(String json);
}
