package com.clara.mcp.transport;

import com.clara.mcp.client.JsonRpcNotification;
import com.clara.mcp.client.JsonRpcRequest;

/**
 * 负责MCPJSON数据的编码与解码。
 */
public interface McpJsonCodec {
    String encode(JsonRpcRequest request);
    String encode(JsonRpcNotification notification);
    McpDecodedMessage decode(String json);
}
