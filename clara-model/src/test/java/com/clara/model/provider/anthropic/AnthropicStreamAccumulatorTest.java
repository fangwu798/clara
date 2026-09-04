package com.clara.model.provider.anthropic;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.FinishReason;
import com.clara.model.api.TextContent;
import com.clara.model.api.ToolCallContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.Test;
import static org.junit.Assert.*;

public class AnthropicStreamAccumulatorTest {
    @Test public void aggregatesTextUsageAndStopReason() {
        AnthropicStreamAccumulator accumulator = accumulator(4, 100);
        accumulator.accept("message_start", start());
        accumulator.accept("content_block_start", textStart(0));
        AnthropicStreamChunk delta = accumulator.accept("content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}");
        accumulator.accept("content_block_stop", stopBlock(0));
        accumulator.accept("message_delta", messageDelta("end_turn", 3));
        AnthropicStreamChunk complete = accumulator.accept("message_stop",
                "{\"type\":\"message_stop\"}");

        assertEquals("hello", delta.getTextDelta());
        ChatResponse response = complete.getResponse();
        assertEquals("hello", ((TextContent) response.getMessage().getContents().get(0)).getText());
        assertEquals(5, response.getTokenUsage().getTotalTokens());
        assertEquals(FinishReason.STOP, response.getFinishReason());
        assertEquals("msg-1", response.getProviderMetadata().get("id"));
        assertTrue(accumulator.isComplete());
    }

    @Test public void aggregatesToolJsonAndCompletesToolAtBlockStop() throws Exception {
        AnthropicStreamAccumulator accumulator = accumulator(4, 100);
        accumulator.accept("message_start", start());
        accumulator.accept("content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{"
                        + "\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"weather\","
                        + "\"input\":{}}}");
        AnthropicStreamChunk first = accumulator.accept("content_block_delta",
                toolDelta(0, "{"));
        accumulator.accept("content_block_delta", toolDelta(0, "\\\"city\\\":\\\"HZ\\\"}"));
        AnthropicStreamChunk stopped = accumulator.accept("content_block_stop", stopBlock(0));
        accumulator.accept("message_delta", messageDelta("tool_use", 6));
        ChatResponse response = accumulator.accept("message_stop",
                "{\"type\":\"message_stop\"}").getResponse();

        assertEquals("call-1", first.getToolCallId());
        assertEquals("weather", first.getToolName());
        assertEquals("{", first.getToolArgumentsDelta());
        assertNotNull(stopped.getCompletedTool());
        ToolCallContent tool = (ToolCallContent) response.getMessage().getContents().get(0);
        assertEquals("HZ", new ObjectMapper().readTree(tool.getArguments()).get("city").asText());
        assertEquals(FinishReason.TOOL_CALLS, response.getFinishReason());
    }

    @Test public void enforcesEventNamesAndLifecycle() {
        assertProtocol(new Runnable() { public void run() {
            accumulator(2, 100).accept("message_delta", start());
        }});
        assertProtocol(new Runnable() { public void run() {
            accumulator(2, 100).accept("content_block_delta",
                    "{\"type\":\"message_start\",\"message\":{}}");
        }});
        final AnthropicStreamAccumulator active = accumulator(2, 100);
        active.accept("message_start", start());
        active.accept("content_block_start", textStart(0));
        assertProtocol(new Runnable() { public void run() {
            active.accept("message_delta", messageDelta("end_turn", 1));
        }});
    }

    @Test public void rejectsInvalidToolJsonAndUnsupportedBlocks() {
        final AnthropicStreamAccumulator tool = accumulator(2, 100);
        tool.accept("message_start", start());
        tool.accept("content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{"
                        + "\"type\":\"tool_use\",\"id\":\"id\",\"name\":\"tool\",\"input\":{}}}");
        tool.accept("content_block_delta", toolDelta(0, "not-json"));
        assertProtocol(new Runnable() { public void run() {
            tool.accept("content_block_stop", stopBlock(0));
        }});
        final AnthropicStreamAccumulator thinking = accumulator(2, 100);
        thinking.accept("message_start", start());
        assertProtocol(new Runnable() { public void run() {
            thinking.accept("content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":0,"
                            + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");
        }});
    }

    @Test public void enforcesLimitsAndMapsSafeProviderErrors() {
        final AnthropicStreamAccumulator blocks = accumulator(1, 100);
        blocks.accept("message_start", start());
        blocks.accept("content_block_start", textStart(0));
        assertCode(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, new Runnable() { public void run() {
            blocks.accept("content_block_start", textStart(1));
        }});
        final AnthropicStreamAccumulator chars = accumulator(2, 3);
        chars.accept("message_start", start());
        chars.accept("content_block_start", textStart(0));
        assertCode(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, new Runnable() { public void run() {
            chars.accept("content_block_delta", "{\"type\":\"content_block_delta\","
                    + "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"four\"}}");
        }});

        try {
            accumulator(2, 100).accept("error", "{\"type\":\"error\",\"error\":{"
                    + "\"type\":\"overloaded_error\",\"message\":\"secret diagnostic\"}}");
            fail("expected provider failure");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.MODEL_PROVIDER_FAILED, exception.getError().getCode());
            assertEquals("overloaded_error", exception.getError().getProviderCode());
            assertTrue(exception.getError().isRetryable());
            assertFalse(exception.getMessage().contains("diagnostic"));
        }
    }

    private static AnthropicStreamAccumulator accumulator(int blocks, int chars) {
        return new AnthropicStreamAccumulator(config(), blocks, chars);
    }

    private static AnthropicChatConfig config() {
        try {
            return new AnthropicChatConfig(new URI("https://api.anthropic.example/v1/messages"),
                    "claude-fixed", "messages-v1", "2023-06-01", "secret-ref", 128);
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    static String start() {
        return "{\"type\":\"message_start\",\"message\":{\"id\":\"msg-1\","
                + "\"role\":\"assistant\",\"model\":\"claude-fixed\",\"content\":[],"
                + "\"usage\":{\"input_tokens\":2}}}";
    }

    static String textStart(int index) {
        return "{\"type\":\"content_block_start\",\"index\":" + index
                + ",\"content_block\":{\"type\":\"text\",\"text\":\"\"}}";
    }

    static String stopBlock(int index) {
        return "{\"type\":\"content_block_stop\",\"index\":" + index + "}";
    }

    static String messageDelta(String reason, int output) {
        return "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + reason
                + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":" + output + "}}";
    }

    static String toolDelta(int index, String fragment) {
        return ("{\"type\":\"content_block_delta\",\"index\":" + index
                + ",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"ARG\"}}")
                .replace("ARG", fragment);
    }

    private static void assertProtocol(Runnable action) {
        assertCode(ErrorCodes.MODEL_PROTOCOL_ERROR, action);
    }

    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException exception) { assertEquals(code, exception.getError().getCode()); }
    }
}
