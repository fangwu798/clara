package com.clara.utils.deserialize;

import com.clara.tool.ResponsesToolCall;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responses 协议工具调用的转换工具：把 {@code output[]} 里的 {@code function_call} 过滤并反序列化为独立类型
 * {@link ResponsesToolCall}，以及把工具调用序列化回 {@code tool_calls[]} 约定供观察者拼装。
 */
public final class ResponseToolCallSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponseToolCallSupport() {
    }

    public static boolean isResponsesToolCallList(Field field) {
        Type type = field.getGenericType();
        if (!(type instanceof ParameterizedType)) {
            return false;
        }
        ParameterizedType parameterized = (ParameterizedType) type;
        return parameterized.getRawType() == List.class
                && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] == ResponsesToolCall.class;
    }

    public static List<ResponsesToolCall> convertResponsesToolCalls(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<ResponsesToolCall> result = new ArrayList<ResponsesToolCall>();
        for (JsonNode item : node) {
            if (!item.isObject() || !"function_call".equals(textOrNull(item.get("type")))) {
                continue;
            }
            ResponsesToolCall call = MAPPER.convertValue(item, ResponsesToolCall.class);
            if (call.getCallId() != null && !call.getCallId().isEmpty()) {
                result.add(call);
            }
        }
        return result.isEmpty() ? null : result;
    }

    public static String toolCallsPayload(List<ResponsesToolCall> calls) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (ResponsesToolCall call : calls) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("index", Integer.valueOf(index++));
            item.put("id", call.getCallId());
            Map<String, Object> function = new LinkedHashMap<String, Object>();
            function.put("name", call.getName());
            function.put("arguments", call.getArguments());
            item.put("function", function);
            items.add(item);
        }
        try {
            return MAPPER.writeValueAsString(items);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize tool calls", exception);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }
}
