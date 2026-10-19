package com.clara.mcp.transport;

import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.McpErrors;
import com.clara.mcp.api.IMcpJsonCodec;
import com.clara.mcp.transport.jsonrpc.JsonRpcError;
import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;
import com.clara.mcp.transport.jsonrpc.JsonRpcResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 负责JacksonMCPJSON数据的编码与解码。
 */
public final class JacksonMcpJsonCodec implements IMcpJsonCodec {
    private static final TypeReference<Map<String, Object>> MAP =
            new TypeReference<Map<String, Object>>() { };
    private final ObjectMapper mapper;
    public JacksonMcpJsonCodec() { this(new ObjectMapper()); }
    public JacksonMcpJsonCodec(ObjectMapper mapper) {
        if (mapper == null) { throw new IllegalArgumentException("mapper must not be null"); }
        this.mapper = mapper;
    }
    public String encode(JsonRpcRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        Map<String, Object> value = envelope(); value.put("id", request.getId());
        value.put("method", request.getMethod()); value.put("params", request.getParams());
        return write(value);
    }
    public String encode(JsonRpcNotification notification) {
        if (notification == null) { throw new IllegalArgumentException("notification must not be null"); }
        Map<String, Object> value = envelope(); value.put("method", notification.getMethod());
        value.put("params", notification.getParams()); return write(value);
    }
    public McpDecodedMessage decode(String json) {
        if (json == null || json.trim().isEmpty()) { throw invalid(); }
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isObject() || !"2.0".equals(text(root.get("jsonrpc")))) { throw invalid(); }
            JsonNode method = root.get("method"); JsonNode id = root.get("id");
            if (method != null && method.isTextual()) {
                Map<String, Object> params = map(root.get("params"));
                return id == null || id.isNull()
                        ? McpDecodedMessage.notification(new JsonRpcNotification(method.textValue(), params))
                        : McpDecodedMessage.serverRequest(new JsonRpcRequest(id.asText(), method.textValue(), params));
            }
            if (id == null || id.isNull()) { throw invalid(); }
            JsonNode result = root.get("result"); JsonNode error = root.get("error");
            if ((result == null) == (error == null)) { throw invalid(); }
            if (error != null) {
                if (!error.isObject() || !error.has("code") || !error.has("message")) { throw invalid(); }
                JsonRpcError decodedError = new JsonRpcError(error.get("code").intValue(),
                        error.get("message").asText(), map(error.get("data")));
                return McpDecodedMessage.response(new JsonRpcResponse(id.asText(), null, decodedError));
            }
            return McpDecodedMessage.response(new JsonRpcResponse(id.asText(), map(result), null));
        } catch (JsonProcessingException exception) {
            throw invalid();
        }
    }
    private String write(Map<String, Object> value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw invalid(); }
    }
    private Map<String, Object> map(JsonNode node) {
        if (node == null || node.isNull()) return Collections.emptyMap();
        if (!node.isObject()) { throw invalid(); }
        return mapper.convertValue(node, MAP);
    }
    private static Map<String, Object> envelope() {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("jsonrpc", "2.0"); return value;
    }
    private static String text(JsonNode value) { return value == null ? null : value.asText(null); }
    private static RuntimeException invalid() {
        return McpErrors.error(ErrorCodes.MCP_INVALID_MESSAGE, ErrorCategoryEnum.VALIDATION, false);
    }
}
