package com.clara.model.provider.compatible;

import com.clara.model.api.ChatResponse;
import com.clara.model.api.Content;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.TextContent;
import com.clara.model.api.TokenUsage;
import com.clara.model.api.ToolCallContent;
import java.net.URI;
import org.junit.Test;
import static org.junit.Assert.*;

public class OpenAiCompatibleSyncStreamParityTest {
    @Test public void producesEquivalentDeepSeekTextAndToolResponses() {
        OpenAiCompatibleChatConfig config = config();
        String full = "{\"id\":\"r1\",\"model\":\"fixed-model\",\"system_fingerprint\":\"fp1\","
                + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello\","
                + "\"reasoning_content\":\"thinking\",\"tool_calls\":[{\"id\":\"call-1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"lookup\","
                + "\"arguments\":\"{\\\"x\\\":1}\"}}]},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":3}}";
        ChatResponse synchronous = new OpenAiCompatibleChatCodec().decode(
                utf8(full), config);

        OpenAiCompatibleStreamAccumulator accumulator =
                new OpenAiCompatibleStreamAccumulator(config, 4, 1000);
        String contentChunk = "{\"id\":\"r1\",\"model\":\"fixed-model\","
                + "\"system_fingerprint\":\"fp1\",\"choices\":[{\"index\":0,\"delta\":{"
                + "\"role\":\"assistant\",\"content\":\"Hello\","
                + "\"reasoning_content\":\"thinking\"},\"finish_reason\":null}]}";
        String toolChunk = ("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{"
                + "\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"lookup\",\"arguments\":\"ARGS\"}}]},"
                + "\"finish_reason\":null}]}").replace("ARGS", "{\\\"x\\\":1}");
        accumulator.accept(contentChunk);
        accumulator.accept(toolChunk);
        accumulator.accept("{\"choices\":[{\"index\":0,\"delta\":{},"
                + "\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":4,"
                + "\"completion_tokens\":3}}");
        ChatResponse streaming = accumulator.accept("[DONE]").getResponse();

        assertEquivalent(synchronous, streaming);
    }

    private static void assertEquivalent(ChatResponse expected, ChatResponse actual) {
        assertEquals(expected.getMessage(), actual.getMessage());
        assertUsage(expected.getTokenUsage(), actual.getTokenUsage());
        assertEquals(expected.getFinishReason(), actual.getFinishReason());
        assertEquals(expected.getProviderMetadata(), actual.getProviderMetadata());
        assertEquals("Hello", ((TextContent) actual.getMessage().getContents().get(0)).getText());
        Content tool = actual.getMessage().getContents().get(1);
        assertTrue(tool instanceof ToolCallContent);
    }

    private static void assertUsage(TokenUsage expected, TokenUsage actual) {
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(expected.getInputTokens(), actual.getInputTokens());
        assertEquals(expected.getOutputTokens(), actual.getOutputTokens());
    }

    private static OpenAiCompatibleChatConfig config() {
        try {
            return new OpenAiCompatibleChatConfig(ModelProvider.DEEPSEEK,
                    new URI("https://api.deepseek.example/chat/completions"), "fixed-model",
                    "chat-completions-v1", "secret-ref");
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static byte[] utf8(String value) {
        try { return value.getBytes("UTF-8"); }
        catch (java.io.UnsupportedEncodingException exception) { throw new AssertionError(exception); }
    }
}
