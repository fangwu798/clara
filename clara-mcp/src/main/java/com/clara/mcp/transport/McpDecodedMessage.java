package com.clara.mcp.transport;

import com.clara.mcp.client.JsonRpcNotification;
import com.clara.mcp.client.JsonRpcRequest;
import com.clara.mcp.client.JsonRpcResponse;

public final class McpDecodedMessage {
    public enum Type { RESPONSE, NOTIFICATION, SERVER_REQUEST }
    private final Type type;
    private final JsonRpcResponse response;
    private final JsonRpcNotification notification;
    private final JsonRpcRequest request;
    private McpDecodedMessage(Type type, JsonRpcResponse response,
            JsonRpcNotification notification, JsonRpcRequest request) {
        this.type = type; this.response = response;
        this.notification = notification; this.request = request;
    }
    public static McpDecodedMessage response(JsonRpcResponse value) {
        return new McpDecodedMessage(Type.RESPONSE, value, null, null);
    }
    public static McpDecodedMessage notification(JsonRpcNotification value) {
        return new McpDecodedMessage(Type.NOTIFICATION, null, value, null);
    }
    public static McpDecodedMessage serverRequest(JsonRpcRequest value) {
        return new McpDecodedMessage(Type.SERVER_REQUEST, null, null, value);
    }
    public Type getType() { return type; }
    public JsonRpcResponse getResponse() { return response; }
    public JsonRpcNotification getNotification() { return notification; }
    public JsonRpcRequest getRequest() { return request; }
}
