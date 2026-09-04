package com.clara.mcp.transport;

import com.clara.mcp.client.JsonRpcNotification;
import com.clara.mcp.client.JsonRpcRequest;

public interface McpJsonCodec {
    String encode(JsonRpcRequest request);
    String encode(JsonRpcNotification notification);
    McpDecodedMessage decode(String json);
}
