package com.clara.model.provider.compatible;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.Content;
import com.clara.model.api.FinishReason;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.Role;
import com.clara.model.api.TextContent;
import com.clara.model.api.ToolCallContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class OpenAiCompatibleStreamAccumulatorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test public void encodesStreamingRequestWithoutChangingSynchronousRequest() throws Exception {
        OpenAiCompatibleChatCodec codec = new OpenAiCompatibleChatCodec();
        ChatRequest request = new ChatRequest(Collections.singletonList(new ChatMessage(Role.USER,
                Collections.<Content>singletonList(new TextContent("hello")), null, null)),
                null, null, null, context());

        JsonNode streaming = json.readTree(codec.encode(request, config(), true));
        JsonNode synchronous = json.readTree(codec.encode(request, config()));
        assertTrue(streaming.get("stream").asBoolean());
        assertFalse(synchronous.has("stream"));
    }

    @Test public void aggregatesTextReasoningToolsUsageAndMetadata() throws Exception {
        OpenAiCompatibleStreamAccumulator accumulator = accumulator(4, 1000);
        String firstJson = ("{\"id\":\"r1\",\"model\":\"fixed-model\",\"system_fingerprint\":\"fp1\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                + "\"content\":\"Hel\",\"reasoning_content\":\"think\",\"tool_calls\":[{"
                + "\"index\":0,\"id\":\"call-\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"weath\",\"arguments\":\"ARG\"}}]},\"finish_reason\":null}]}")
                .replace("ARG", "{");
        OpenAiCompatibleStreamChunk first = accumulator.accept(firstJson);
        String secondJson = ("{\"id\":\"r1\",\"model\":\"fixed-model\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"lo\",\"reasoning_content\":\"ing\","
                + "\"tool_calls\":[{\"index\":0,\"id\":\"1\",\"function\":{\"name\":\"er\","
                + "\"arguments\":\"ARG\"}}]},\"finish_reason\":null}]}")
                .replace("ARG", "\\\"city\\\":\\\"HZ\\\"}");
        OpenAiCompatibleStreamChunk second = accumulator.accept(secondJson);
        accumulator.accept("{\"choices\":[{\"index\":0,\"delta\":{},"
                + "\"finish_reason\":\"tool_calls\"}]}");
        accumulator.accept("{\"choices\":[],\"usage\":{\"prompt_tokens\":7,"
                + "\"completion_tokens\":5}}");
        OpenAiCompatibleStreamChunk done = accumulator.accept("[DONE]");

        assertEquals("Hel", first.getTextDelta());
        assertEquals("think", first.getReasoningDelta());
        assertEquals("{", first.getToolDeltas().get(0).getArgumentsDelta());
        assertEquals("call-1", second.getToolDeltas().get(0).getId());
        assertEquals("weather", second.getToolDeltas().get(0).getName());
        assertTrue(done.isComplete());
        ChatResponse response = done.getResponse();
        assertEquals(FinishReason.TOOL_CALLS, response.getFinishReason());
        assertEquals(12, response.getTokenUsage().getTotalTokens());
        assertEquals("Hello", ((TextContent) response.getMessage().getContents().get(0)).getText());
        ToolCallContent call = (ToolCallContent) response.getMessage().getContents().get(1);
        assertEquals("call-1", call.getId());
        assertEquals("weather", call.getName());
        assertEquals("HZ", json.readTree(call.getArguments()).get("city").asText());
        assertEquals("thinking", response.getProviderMetadata().get("reasoning"));
        assertEquals("r1", response.getProviderMetadata().get("id"));
        assertEquals("OPENAI", response.getProviderMetadata().get("provider"));
        assertTrue(accumulator.isComplete());
    }

    @Test public void acceptsEmptyTextAndMapsFinishReasons() {
        OpenAiCompatibleStreamAccumulator accumulator = accumulator(1, 100);
        accumulator.accept("{\"choices\":[{\"delta\":{\"content\":\"\"},"
                + "\"finish_reason\":\"length\"}]}");
        ChatResponse response = accumulator.accept("[DONE]").getResponse();
        assertEquals(FinishReason.LENGTH, response.getFinishReason());
        assertEquals("", ((TextContent) response.getMessage().getContents().get(0)).getText());
    }

    @Test public void rejectsMalformedChunksMetadataDriftAndMissingCompletion() {
        assertProtocol(new Runnable() { public void run() {
            accumulator(2, 100).accept("{\"choices\":[{},{}]}");
        }});
        assertProtocol(new Runnable() { public void run() {
            accumulator(2, 100).accept("{\"choices\":[{\"index\":1,\"delta\":{}}]}");
        }});
        final OpenAiCompatibleStreamAccumulator drift = accumulator(2, 100);
        drift.accept("{\"id\":\"one\",\"choices\":[{\"delta\":{},\"finish_reason\":null}]}");
        assertProtocol(new Runnable() { public void run() {
            drift.accept("{\"id\":\"two\",\"choices\":[{\"delta\":{}}]}");
        }});
        assertProtocol(new Runnable() { public void run() {
            accumulator(2, 100).accept("[DONE]");
        }});
    }

    @Test public void enforcesToolAndAccumulationLimits() {
        assertCode(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, new Runnable() { public void run() {
            accumulator(1, 100).accept("{\"choices\":[{\"delta\":{\"tool_calls\":["
                    + "{\"index\":0,\"id\":\"a\"},{\"index\":1,\"id\":\"b\"}]}}]}");
        }});
        final OpenAiCompatibleStreamAccumulator limited = accumulator(2, 3);
        limited.accept("{\"choices\":[{\"delta\":{\"content\":\"abc\"}}]}");
        assertCode(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, new Runnable() { public void run() {
            limited.accept("{\"choices\":[{\"delta\":{\"content\":\"d\"}}]}");
        }});
    }

    @Test public void rejectsEventsAfterDoneAndConflictingUsage() {
        final OpenAiCompatibleStreamAccumulator complete = accumulator(1, 100);
        complete.accept("{\"choices\":[{\"delta\":{\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
                + "\"completion_tokens\":1}}");
        complete.accept("[DONE]");
        assertProtocol(new Runnable() { public void run() { complete.accept("[DONE]"); }});

        final OpenAiCompatibleStreamAccumulator usage = accumulator(1, 100);
        usage.accept("{\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");
        assertProtocol(new Runnable() { public void run() {
            usage.accept("{\"choices\":[],\"usage\":{\"prompt_tokens\":2,"
                    + "\"completion_tokens\":1}}");
        }});
    }

    private static OpenAiCompatibleStreamAccumulator accumulator(int maxTools, int maxChars) {
        return new OpenAiCompatibleStreamAccumulator(config(), maxTools, maxChars);
    }

    private static OpenAiCompatibleChatConfig config() {
        try {
            return new OpenAiCompatibleChatConfig(ModelProvider.OPENAI,
                    new URI("https://provider.example/v1/chat/completions"), "fixed-model",
                    "chat-completions-v1", "secret-ref");
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static InvocationContext context() {
        return new InvocationContext("tenant", null, null, "trace",
                System.currentTimeMillis() + 5000, new CancellationToken());
    }

    private static void assertProtocol(Runnable action) {
        assertCode(ErrorCodes.MODEL_PROTOCOL_ERROR, action);
    }

    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException exception) { assertEquals(code, exception.getError().getCode()); }
    }
}
