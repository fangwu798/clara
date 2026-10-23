package com.clara.kernel.message.provider.messages;

import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallFunction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages 协议的工具调用转换工具。
 *
 * <p>Messages API 的工具调用形状与 OpenAI 兼容家族不同：响应里是 {@code content[]} 中
 * {@code {"type":"tool_use","id","name","input"}} 的块，入参是 JSON 对象而不是字符串；
 * 流式下则先给 {@code content_block_start}（带 id / name），再用 {@code input_json_delta} 分片下发。
 * 本类把这些形状统一转换成 Clara 的 {@link ToolCall} 与观察者可拼装的分片负载。
 */
final class MessagesToolCalls {

    /**
     * 分片负载的 JSON 序列化器。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessagesToolCalls() {
    }

    /**
     * 判断字段是否为 {@code List<ToolCall>}。
     *
     * @param field 响应字段，非 null
     * @return 是工具调用列表时返回 true
     */
    static boolean isToolCallList(Field field) {
        Type type = field.getGenericType();
        if (!(type instanceof ParameterizedType)) {
            return false;
        }
        ParameterizedType parameterized = (ParameterizedType) type;
        return parameterized.getRawType() == List.class
                && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] == ToolCall.class;
    }

    /**
     * 把同步响应里的 {@code tool_use} 块转换为工具调用列表。
     *
     * @param node {@code content[]} 数组或单个 {@code tool_use} 块，可为 null
     * @return 工具调用列表；没有命中时返回 null
     */
    static List<ToolCall> fromContent(JsonNode node) {
        if (node == null) {
            return null;
        }
        List<ToolCall> calls = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                addToolCall(calls, item);
            }
        } else {
            addToolCall(calls, node);
        }
        return calls.isEmpty() ? null : calls;
    }

    /**
     * 创建流式工具参数分片负载：{@code [{"index":0,"id":"...","function":{"name":"...","arguments":"..."}}]}。
     *
     * @param index     内容块下标
     * @param id        工具调用 ID，可为 null
     * @param name      工具名，可为 null
     * @param arguments 本次参数片段，可为 null
     * @return 观察者可拼装的 JSON 文本，非 null
     */
    static String fragment(int index, String id, String name, String arguments) {
        Map<String, Object> function = new LinkedHashMap<>();
        if (name != null) {
            function.put("name", name);
        }
        function.put("arguments", arguments == null ? "" : arguments);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("index", index);
        if (id != null) {
            item.put("id", id);
        }
        item.put("function", function);
        try {
            return MAPPER.writeValueAsString(Collections.singletonList(item));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize tool argument fragment", exception);
        }
    }

    private static void addToolCall(List<ToolCall> calls, JsonNode item) {
        if (item == null || !item.isObject() || !"tool_use".equals(text(item.get("type")))) {
            return;
        }
        ToolCall call = new ToolCall();
        call.setId(text(item.get("id")));
        call.setType("function");
        ToolCallFunction function = new ToolCallFunction();
        function.setName(text(item.get("name")));
        JsonNode input = item.get("input");
        function.setArguments(input == null || input.isNull() ? "{}" : input.toString());
        call.setFunction(function);
        calls.add(call);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

}
