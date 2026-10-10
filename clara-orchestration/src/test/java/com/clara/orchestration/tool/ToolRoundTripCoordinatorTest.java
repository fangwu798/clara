package com.clara.orchestration.tool;

import com.clara.core.message.IContent;
import com.clara.core.skill.IToolInvoker;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.core.tool.IToolInvocation;
import com.clara.dto.tool.ResponsesToolCall;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallContent;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.dto.tool.ToolResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link ToolRoundTripCoordinator} 单元测试：工具调用检测、Chat / Responses 统一、结果消息回填顺序。
 */
public class ToolRoundTripCoordinatorTest {

    // ---- hasToolCalls ------------------------------------------------------

    @Test
    public void hasToolCallsReturnsFalseForNullResponse() {
        ToolRoundTripCoordinator coordinator = new ToolRoundTripCoordinator(new RecordingInvoker());
        assertFalse(coordinator.hasToolCalls(null));
    }

    @Test
    public void hasToolCallsReturnsFalseWhenNoCallsPresent() {
        ToolRoundTripCoordinator coordinator = new ToolRoundTripCoordinator(new RecordingInvoker());
        HttpResponse response = new HttpResponse();
        assertFalse(coordinator.hasToolCalls(response));

        response.setToolCalls(Collections.<ToolCall>emptyList());
        response.setResponsesToolCalls(Collections.<ResponsesToolCall>emptyList());
        assertFalse(coordinator.hasToolCalls(response));
    }

    @Test
    public void hasToolCallsReturnsTrueForChatAndResponsesCalls() {
        ToolRoundTripCoordinator coordinator = new ToolRoundTripCoordinator(new RecordingInvoker());

        HttpResponse chat = new HttpResponse();
        chat.setToolCalls(Collections.singletonList(chatCall("c1", "f", "{}")));
        assertTrue(coordinator.hasToolCalls(chat));

        HttpResponse responses = new HttpResponse();
        responses.setResponsesToolCalls(Collections.singletonList(responsesCall("r1", "f", "{}")));
        assertTrue(coordinator.hasToolCalls(responses));
    }

    // ---- perform -----------------------------------------------------------

    @Test
    public void constructorRejectsNullInvoker() {
        assertThrows(IllegalArgumentException.class, () -> new ToolRoundTripCoordinator(null));
    }

    @Test
    public void performRejectsNullResponse() {
        ToolRoundTripCoordinator coordinator = new ToolRoundTripCoordinator(new RecordingInvoker());
        assertThrows(IllegalArgumentException.class, () -> coordinator.perform(null));
    }

    @Test
    public void performChatCallsProducesCallContentThenAlignedToolResults() {
        RecordingInvoker invoker = new RecordingInvoker();
        ToolRoundTripCoordinator coordinator = new ToolRoundTripCoordinator(invoker);

        HttpResponse response = new HttpResponse();
        response.setToolCalls(Arrays.asList(chatCall("call-1", "get_weather", "{\"city\":\"上海\"}"),
                chatCall("call-2", "get_time", "{}")));

        List<IContent> messages = coordinator.perform(response);

        assertEquals(3, messages.size());
        assertTrue(messages.get(0) instanceof ToolCallContent);
        ToolCallContent callContent = (ToolCallContent) messages.get(0);
        assertEquals(2, callContent.getToolCalls().size());

        assertTrue(messages.get(1) instanceof ToolContent);
        assertEquals("call-1", ((ToolContent) messages.get(1)).getToolCallId());
        assertTrue(messages.get(2) instanceof ToolContent);
        assertEquals("call-2", ((ToolContent) messages.get(2)).getToolCallId());

        assertEquals(Arrays.asList("call-1", "call-2"), invoker.callIds);
    }

    @Test
    public void performResponsesCallsAreUnifiedIntoToolCallThenAlignedResults() {
        RecordingInvoker invoker = new RecordingInvoker();
        ToolRoundTripCoordinator coordinator = new ToolRoundTripCoordinator(invoker);

        HttpResponse response = new HttpResponse();
        response.setResponsesToolCalls(Collections.singletonList(
                responsesCall("resp-1", "search", "{\"q\":\"clara\"}")));

        List<IContent> messages = coordinator.perform(response);

        assertEquals(2, messages.size());
        ToolCallContent callContent = (ToolCallContent) messages.get(0);
        assertEquals(1, callContent.getToolCalls().size());
        ToolCall unified = callContent.getToolCalls().get(0);
        assertEquals("resp-1", unified.getId());
        assertEquals("search", unified.getFunction().getName());
        assertEquals("{\"q\":\"clara\"}", unified.getFunction().getArguments());
        assertEquals("function", unified.getType());

        assertEquals("resp-1", ((ToolContent) messages.get(1)).getToolCallId());
        assertEquals(Collections.singletonList("resp-1"), invoker.callIds);
    }

    // ---- helpers -----------------------------------------------------------

    private static ToolCall chatCall(String id, String name, String arguments) {
        ToolCallFunction function = new ToolCallFunction();
        function.setName(name);
        function.setArguments(arguments);
        ToolCall call = new ToolCall();
        call.setId(id);
        call.setType("function");
        call.setFunction(function);
        return call;
    }

    private static ResponsesToolCall responsesCall(String callId, String name, String arguments) {
        ResponsesToolCall call = new ResponsesToolCall();
        call.setCallId(callId);
        call.setName(name);
        call.setArguments(arguments);
        return call;
    }

    private static final class RecordingInvoker implements IToolInvoker {

        private final List<String> callIds = new ArrayList<String>();

        @Override
        public ToolResult invoke(IToolInvocation invocation) {
            callIds.add(invocation.callId());
            return new ToolResult(invocation.callId(), "{\"ok\":true}");
        }
    }
}
