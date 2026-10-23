package com.clara.dto.tool;

import com.clara.core.tool.IToolInvocation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ResponsesToolCall} 的契约测试：扁平线格式与统一工具调用视图。
 *
 * <p>Responses 的 function_call 与 Chat Completions 结构不同，字段名必须严格匹配
 * {@code call_id}/{@code name}/{@code arguments}，否则工具结果无法回填。
 */
public class ResponsesToolCallTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void implementsUnifiedToolInvocationView() {
        ResponsesToolCall call = new ResponsesToolCall();
        call.setType("function_call");
        call.setCallId("call_1");
        call.setName("search");
        call.setArguments("{\"q\":\"x\"}");

        IToolInvocation invocation = call;
        assertEquals("call_1", invocation.callId());
        assertEquals("search", invocation.name());
        assertEquals("{\"q\":\"x\"}", invocation.arguments());
    }

    @Test
    public void serializesWithResponsesFieldNames() throws Exception {
        ResponsesToolCall call = new ResponsesToolCall();
        call.setType("function_call");
        call.setCallId("call_1");
        call.setName("search");
        call.setArguments("{}");

        String json = MAPPER.writeValueAsString(call);

        assertTrue(json.contains("\"call_id\":\"call_1\""));
        assertTrue(json.contains("\"name\":\"search\""));
        assertTrue(json.contains("\"type\":\"function_call\""));
    }

    @Test
    public void comparesByAllFields() {
        ResponsesToolCall one = call("call_1", "search");
        ResponsesToolCall same = call("call_1", "search");
        ResponsesToolCall other = call("call_2", "search");

        assertEquals(one, same);
        assertEquals(one.hashCode(), same.hashCode());
        assertFalse(one.equals(other));
        assertFalse(one.equals("other"));
        assertEquals("call_1", one.getCallId());
        assertEquals("search", one.getName());
        assertEquals("{}", one.getArguments());
        assertTrue(one.toString().contains("call_1"));
    }

    private static ResponsesToolCall call(String id, String name) {
        ResponsesToolCall call = new ResponsesToolCall();
        call.setType("function_call");
        call.setCallId(id);
        call.setName(name);
        call.setArguments("{}");
        return call;
    }
}