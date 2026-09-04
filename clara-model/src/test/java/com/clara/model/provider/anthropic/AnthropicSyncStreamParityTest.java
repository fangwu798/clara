package com.clara.model.provider.anthropic;

import com.clara.model.api.ChatResponse;
import com.clara.model.api.TokenUsage;
import java.net.URI;
import org.junit.Test;
import static org.junit.Assert.*;

public class AnthropicSyncStreamParityTest {
    @Test public void producesEquivalentTextAndToolResponses() {
        AnthropicChatConfig config = config();
        String full = "{\"id\":\"msg-1\",\"model\":\"claude-fixed\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Hello\"},{\"type\":\"tool_use\","
                + "\"id\":\"call-1\",\"name\":\"lookup\",\"input\":{\"x\":1}}],"
                + "\"stop_reason\":\"tool_use\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":4,\"output_tokens\":3}}";
        ChatResponse synchronous = new AnthropicChatCodec().decode(utf8(full), config);

        AnthropicStreamAccumulator accumulator = new AnthropicStreamAccumulator(config, 4, 1000);
        accumulator.accept("message_start", "{\"type\":\"message_start\",\"message\":{"
                + "\"id\":\"msg-1\",\"role\":\"assistant\",\"model\":\"claude-fixed\","
                + "\"content\":[],\"usage\":{\"input_tokens\":4}}}");
        accumulator.accept("content_block_start", AnthropicStreamAccumulatorTest.textStart(0));
        accumulator.accept("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}");
        accumulator.accept("content_block_stop", AnthropicStreamAccumulatorTest.stopBlock(0));
        accumulator.accept("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"call-1\","
                + "\"name\":\"lookup\",\"input\":{}}}");
        accumulator.accept("content_block_delta",
                AnthropicStreamAccumulatorTest.toolDelta(1, "{\\\"x\\\":1}"));
        accumulator.accept("content_block_stop", AnthropicStreamAccumulatorTest.stopBlock(1));
        accumulator.accept("message_delta",
                AnthropicStreamAccumulatorTest.messageDelta("tool_use", 3));
        ChatResponse streaming = accumulator.accept("message_stop",
                "{\"type\":\"message_stop\"}").getResponse();

        assertEquals(synchronous.getMessage(), streaming.getMessage());
        assertUsage(synchronous.getTokenUsage(), streaming.getTokenUsage());
        assertEquals(synchronous.getFinishReason(), streaming.getFinishReason());
        assertEquals(synchronous.getProviderMetadata(), streaming.getProviderMetadata());
    }

    private static void assertUsage(TokenUsage expected, TokenUsage actual) {
        assertEquals(expected.getInputTokens(), actual.getInputTokens());
        assertEquals(expected.getOutputTokens(), actual.getOutputTokens());
    }

    private static AnthropicChatConfig config() {
        try {
            return new AnthropicChatConfig(new URI("https://api.anthropic.example/v1/messages"),
                    "claude-fixed", "messages-v1", "2023-06-01", "secret-ref", 128);
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static byte[] utf8(String value) {
        try { return value.getBytes("UTF-8"); }
        catch (java.io.UnsupportedEncodingException exception) { throw new AssertionError(exception); }
    }
}
