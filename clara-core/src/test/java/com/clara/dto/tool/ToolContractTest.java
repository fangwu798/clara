package com.clara.dto.tool;

import com.clara.enums.RoleEnum;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 工具请求/响应契约：Chat Completions 与 Responses 两种线格式的渲染。
 */
public class ToolContractTest {

    /**
     * 工具声明按两种协议渲染，web_search 不携带函数签名。
     */
    @Test
    public void rendersToolDeclarations() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        ToolSpecification spec = new ToolSpecification("search", "search docs", parameters);
        assertEquals("search", spec.getName());
        assertEquals("search docs", spec.getDescription());
        assertEquals(parameters, spec.getParameters());

        ToolContext context = new ToolContext(spec);
        assertEquals("function", context.getType());
        Map<String, Object> completion = context.toCompletionTool();
        assertEquals("function", completion.get("type"));
        assertEquals("search", ((Map<?, ?>) completion.get("function")).get("name"));

        Map<String, Object> response = context.toResponseTool();
        assertEquals("search", response.get("name"));
        assertEquals(Boolean.TRUE, response.get("strict"));

        ToolContext webSearch = new ToolContext("web_search", null);
        assertEquals("web_search", webSearch.getType());
        assertEquals(Collections.singletonMap("type", "web_search"), webSearch.toCompletionTool());
        assertEquals(Collections.singletonMap("type", "web_search"), webSearch.toResponseTool());
        webSearch.setType("function");
        assertEquals("function", webSearch.getType());
    }

    /**
     * 助手工具调用消息：角色固定、携带文本与全部调用。
     */
    @Test
    public void rendersToolCallContent() {
        ToolCallFunction function = new ToolCallFunction();
        function.setName("search");
        function.setArguments("{\"q\":\"x\"}");
        ToolCall call = new ToolCall();
        call.setId("call_1");
        call.setType("function");
        call.setFunction(function);
        assertEquals("call_1", call.callId());
        assertEquals(function, call.getFunction());

        ToolCallContent content = new ToolCallContent("thinking", Collections.singletonList(call));
        assertEquals("assistant", content.getRole());
        assertEquals(RoleEnum.TOOL_CALL, content.getRoleType());
        assertEquals("thinking", content.getContent());
        assertEquals(1, content.getToolCalls().size());

        Map<String, Object> message = content.toCompletionMessage();
        assertEquals("assistant", message.get("role"));
        assertEquals("thinking", message.get("content"));
        assertEquals(1, ((List<?>) message.get("tool_calls")).size());

        List<Map<String, Object>> items = content.toResponseInputItems();
        assertEquals(2, items.size());
        assertEquals("message", items.get(0).get("type"));
        assertEquals("assistant", items.get(0).get("role"));
        assertEquals("function_call", items.get(1).get("type"));
        assertEquals("call_1", items.get(1).get("call_id"));
        assertEquals(function.getArguments(), items.get(1).get("arguments"));

        List<Map<String, Object>> callOnly = new ToolCallContent(Collections.singletonList(call)).toResponseInputItems();
        assertEquals(1, callOnly.size());
        assertEquals("function_call", callOnly.get(0).get("type"));

        assertNull(new ToolCallContent(Collections.singletonList(call)).getContent());
        assertThrows(IllegalArgumentException.class, () -> new ToolCallContent(null));
        assertThrows(IllegalArgumentException.class, () -> new ToolCallContent(Collections.emptyList()));
    }

    /**
     * 工具结果消息：角色为 tool，两种协议字段正确。
     */
    @Test
    public void rendersToolResult() {
        ToolResult result = new ToolResult("call_1", "{\"ok\":true}");
        assertEquals("call_1", result.getCallId());
        assertEquals("{\"ok\":true}", result.getOutput());
        assertEquals(RoleEnum.TOOL, result.getRoleType());

        Map<String, Object> message = result.toCompletionMessage();
        assertEquals("tool", message.get("role"));
        assertEquals("call_1", message.get("tool_call_id"));
        assertEquals("{\"ok\":true}", message.get("content"));

        List<Map<String, Object>> items = result.toResponseInputItems();
        assertEquals("function_call_output", items.get(0).get("type"));
        assertEquals("call_1", items.get(0).get("call_id"));
        assertEquals("{\"ok\":true}", items.get(0).get("output"));

        ToolResult empty = new ToolResult(null, null);
        assertNull(empty.getCallId());
        assertNull(empty.getOutput());
        assertFalse(empty.toResponseInputItems().isEmpty());
        assertTrue(empty.toCompletionMessage().containsKey("content"));
        assertEquals(Arrays.asList("role", "tool_call_id", "content"),
                new java.util.ArrayList<>(empty.toCompletionMessage().keySet()));
    }

    /**
     * Messages 协议工具声明没有 type 外层、Schema 键名为 input_schema；无函数签名时为空映射。
     */
    @Test
    public void rendersMessagesToolDeclarations() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        ToolSpecification spec = new ToolSpecification("search", "search docs", parameters);

        Map<String, Object> messages = new ToolContext(spec).toMessagesTool();
        assertEquals("search", messages.get("name"));
        assertEquals("search docs", messages.get("description"));
        assertEquals(parameters, messages.get("input_schema"));
        assertFalse(messages.containsKey("type"));
        assertFalse(messages.containsKey("parameters"));

        ToolSpecification noDescription = new ToolSpecification("search", null, parameters);
        assertFalse(new ToolContext(noDescription).toMessagesTool().containsKey("description"));
        assertTrue(new ToolContext("web_search", null).toMessagesTool().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> new ToolSpecification(" ", "d", parameters));
        assertThrows(IllegalArgumentException.class, () -> new ToolSpecification("search", "d", null));

        Map<String, Object> mutable = new HashMap<>();
        mutable.put("type", "object");
        ToolSpecification copied = new ToolSpecification("search", "d", mutable);
        mutable.put("type", "changed");
        assertEquals("object", copied.getParameters().get("type"));
        assertThrows(UnsupportedOperationException.class, () -> copied.getParameters().put("x", 1));
    }
}