package com.clara.memory.persistence;

import com.clara.core.message.IContent;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallContent;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.exception.ClaraException;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Round-trip and corruption handling of the conversation codec.
 */
public class ConversationCodecTest {

    private final ConversationCodec codec = new ConversationCodec();

    @Test
    public void roundTripsAllSupportedMessageKinds() {
        ToolCall call = new ToolCall();
        call.setId("call-1");
        call.setType("function");
        ToolCallFunction function = new ToolCallFunction();
        function.setName("echo");
        function.setArguments("{\"a\":1}");
        call.setFunction(function);

        List<IContent> messages = Arrays.asList(
                new SystemContent("sys"),
                new UserContent("hello"),
                new AssistantContent("plain"),
                new ToolCallContent(null, Collections.singletonList(call)),
                new ToolContent("call-1", "result"));
        ConversationSnapshot snapshot = new ConversationSnapshot(7L, messages);

        ConversationSnapshot decoded = codec.decode(codec.encode(snapshot));

        assertEquals(7L, decoded.version());
        assertEquals(5, decoded.messages().size());
        assertTrue(decoded.messages().get(0) instanceof SystemContent);
        assertTrue(decoded.messages().get(1) instanceof UserContent);
        assertTrue(decoded.messages().get(2) instanceof AssistantContent);
        assertTrue(decoded.messages().get(3) instanceof ToolCallContent);
        assertTrue(decoded.messages().get(4) instanceof ToolContent);
        ToolCall decodedCall = ((ToolCallContent) decoded.messages().get(3)).getToolCalls().get(0);
        assertEquals("call-1", decodedCall.getId());
        assertEquals("echo", decodedCall.getFunction().getName());
    }

    @Test
    public void roundTripsEmptyAssistantAndToolCallWithoutFunction() {
        ToolCall call = new ToolCall();
        call.setId("c");
        List<IContent> messages = Arrays.asList(new AssistantContent(null),
                new ToolCallContent("x", Collections.singletonList(call)));
        ConversationSnapshot decoded = codec.decode(codec.encode(new ConversationSnapshot(0L, messages)));
        assertEquals(2, decoded.messages().size());
        assertTrue(decoded.messages().get(0) instanceof AssistantContent);
        assertEquals("c", ((ToolCallContent) decoded.messages().get(1)).getToolCalls().get(0).getId());
    }

    @Test
    public void rejectsNullArguments() {
        assertIllegalArgument(() -> codec.encode(null));
        assertIllegalArgument(() -> codec.decode(null));
    }

    @Test
    public void rejectsCorruptedPayloads() {
        assertCorrupted("not-json");
        assertCorrupted("{}");
        assertCorrupted("{\"version\":\"1\",\"messages\":[]}");
        assertCorrupted("{\"version\":1}");
        assertCorrupted("{\"version\":1,\"messages\":{}}");
        assertCorrupted("{\"version\":1,\"messages\":[1]}");
        assertCorrupted("{\"version\":1,\"messages\":[{}]}");
        assertCorrupted("{\"version\":1,\"messages\":[{\"role\":1}]}");
        assertCorrupted("{\"version\":1,\"messages\":[{\"role\":\"unknown\"}]}");
        assertCorrupted("{\"version\":1,\"messages\":[{\"role\":\"assistant\",\"tool_calls\":[1]}]}");
    }

    private void assertCorrupted(String json) {
        try {
            codec.decode(json.getBytes(StandardCharsets.UTF_8));
            fail("expected ClaraException for " + json);
        } catch (ClaraException expected) {
            assertEquals("CONVERSATION_STORE_CORRUPTED", expected.getError().getCode());
        }
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
