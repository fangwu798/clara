package com.clara.model.provider.anthropic;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.Content;
import com.clara.model.api.FinishReason;
import com.clara.model.api.MediaContent;
import com.clara.model.api.ModelParameters;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.ProviderOptions;
import com.clara.model.api.Role;
import com.clara.model.api.TextContent;
import com.clara.model.api.ToolCallContent;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpRequest;
import com.clara.model.provider.http.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.tool.ToolSpecification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class AnthropicChatModelTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test public void mapsSystemMessagesToolsParametersAndHeaders() throws Exception {
        FakeTransport transport = new FakeTransport(response(200, textResponse("done", "end_turn")));
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("required", Collections.singletonList("city"));
        ToolSpecification tool = new ToolSpecification("weather", "Get weather", schema);
        ModelParameters parameters = new ModelParameters("claude-fixed", Double.valueOf(0.2),
                Double.valueOf(0.8), Integer.valueOf(20), Integer.valueOf(256), null, null,
                null, Collections.singletonList("END"), false);
        ChatRequest request = new ChatRequest(Arrays.asList(text(Role.SYSTEM, "be concise"),
                text(Role.USER, "weather?")), parameters, Collections.singletonList(tool),
                null, context());

        ChatResponse result = model(transport, secret()).chat(request);
        JsonNode sent = json.readTree(transport.request.getBody());
        assertEquals("claude-fixed", sent.get("model").asText());
        assertEquals(256, sent.get("max_tokens").asInt());
        assertFalse(sent.get("stream").asBoolean());
        assertEquals("be concise", sent.get("system").get(0).get("text").asText());
        assertEquals("weather?", sent.get("messages").get(0).get("content").get(0).get("text").asText());
        assertEquals(0.2d, sent.get("temperature").asDouble(), 0.0d);
        assertEquals(0.8d, sent.get("top_p").asDouble(), 0.0d);
        assertEquals(20, sent.get("top_k").asInt());
        assertEquals("END", sent.get("stop_sequences").get(0).asText());
        assertEquals("object", sent.get("tools").get(0).get("input_schema").get("type").asText());
        assertEquals("secret-value", transport.request.getHeaders().get("x-api-key"));
        assertEquals("2023-06-01", transport.request.getHeaders().get("anthropic-version"));
        assertEquals("done", ((TextContent) result.getMessage().getContents().get(0)).getText());
        assertEquals(FinishReason.STOP, result.getFinishReason());
    }

    @Test public void mapsToolUseAndUsageInBothDirections() throws Exception {
        FakeTransport transport = new FakeTransport(response(200,
                "{\"id\":\"msg-1\",\"model\":\"claude-fixed\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"checking\"},{\"type\":\"tool_use\","
                + "\"id\":\"call-2\",\"name\":\"weather\",\"input\":{\"city\":\"HZ\"}}],"
                + "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":7,\"output_tokens\":4}}"));
        List<Content> assistant = Arrays.<Content>asList(new TextContent("calling"),
                new ToolCallContent("call-1", "weather", "{\"city\":\"SH\"}"));
        ChatRequest request = new ChatRequest(Arrays.asList(
                new ChatMessage(Role.ASSISTANT, assistant, null, null),
                new ChatMessage(Role.TOOL, Collections.<Content>singletonList(
                        new TextContent("sunny")), "call-1", null)), null, null, null, context());

        ChatResponse result = model(transport, secret()).chat(request);
        JsonNode messages = json.readTree(transport.request.getBody()).get("messages");
        JsonNode sentCall = messages.get(0).get("content").get(1);
        assertEquals("tool_use", sentCall.get("type").asText());
        assertEquals("SH", sentCall.get("input").get("city").asText());
        JsonNode sentResult = messages.get(1).get("content").get(0);
        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("call-1", sentResult.get("tool_use_id").asText());
        assertEquals("sunny", sentResult.get("content").asText());
        assertEquals(FinishReason.TOOL_CALLS, result.getFinishReason());
        assertEquals(11, result.getTokenUsage().getTotalTokens());
        ToolCallContent received = (ToolCallContent) result.getMessage().getContents().get(1);
        assertEquals("call-2", received.getId());
        assertEquals("weather", received.getName());
        assertEquals("HZ", json.readTree(received.getArguments()).get("city").asText());
        assertEquals("ANTHROPIC", result.getProviderMetadata().get("provider"));
        assertEquals("messages-v1", result.getProviderMetadata().get("apiProfile"));
    }

    @Test public void rejectsInvalidConversationAndToolArguments() {
        final AnthropicChatModel model = model(new FakeTransport(response(200,
                textResponse("ok", "end_turn"))), secret());
        final ChatRequest middleSystem = new ChatRequest(Arrays.asList(text(Role.USER, "x"),
                text(Role.SYSTEM, "late")), null, null, null, context());
        assertIllegal(new Runnable() { public void run() { model.chat(middleSystem); }});

        final ChatMessage badToolCall = new ChatMessage(Role.ASSISTANT,
                Collections.<Content>singletonList(new ToolCallContent("id", "tool", "[]")),
                null, null);
        assertIllegal(new Runnable() { public void run() {
            model.chat(new ChatRequest(Collections.singletonList(badToolCall),
                    null, null, null, context()));
        }});
    }

    @Test public void rejectsUnsupportedCapabilitiesModelOverrideAndForeignOptions() {
        final AnthropicChatModel model = model(new FakeTransport(response(200,
                textResponse("ok", "end_turn"))), secret());
        final ChatMessage media = new ChatMessage(Role.USER,
                Collections.<Content>singletonList(new MediaContent("image", "asset")), null, null);
        assertCode(ErrorCodes.CAPABILITY_NOT_SUPPORTED, new Runnable() { public void run() {
            model.chat(new ChatRequest(Collections.singletonList(media), null, null, null, context()));
        }});
        final ModelParameters unsupported = new ModelParameters(null, null, null, null, null,
                Double.valueOf(0.1), null, null, null, false);
        assertCode(ErrorCodes.CAPABILITY_NOT_SUPPORTED, new Runnable() { public void run() {
            model.chat(new ChatRequest(Collections.singletonList(text(Role.USER, "x")),
                    unsupported, null, null, context()));
        }});
        final ModelParameters wrongModel = new ModelParameters("other", null, null, null, null, false);
        assertIllegal(new Runnable() { public void run() {
            model.chat(new ChatRequest(Collections.singletonList(text(Role.USER, "x")),
                    wrongModel, null, null, context()));
        }});
        ProviderOptions foreign = new ProviderOptions() {
            public ModelProvider getProvider() { return ModelProvider.OPENAI; }
        };
        final ChatRequest foreignRequest = new ChatRequest(
                Collections.singletonList(text(Role.USER, "x")), null, null, foreign, context());
        assertIllegal(new Runnable() { public void run() { model.chat(foreignRequest); }});
        ProviderOptions unknown = new ProviderOptions() {
            public ModelProvider getProvider() { return ModelProvider.ANTHROPIC; }
        };
        final ChatRequest unknownRequest = new ChatRequest(
                Collections.singletonList(text(Role.USER, "x")), null, null, unknown, context());
        assertIllegal(new Runnable() { public void run() { model.chat(unknownRequest); }});
    }

    @Test public void mapsProviderFailuresWithoutExposingBodies() {
        assertFailure(401, ErrorCategory.AUTHENTICATION, false);
        assertFailure(429, ErrorCategory.RATE_LIMIT, true);
        assertFailure(503, ErrorCategory.EXTERNAL_SERVICE, true);
    }

    @Test public void rejectsMissingCredentialMalformedAndUnknownResponseBlocks() {
        final AnthropicChatModel missing = model(new FakeTransport(response(200,
                textResponse("ok", "end_turn"))), new ProviderCredentialResolver() {
            public char[] resolve(String tenantId, String secretRef) { return null; }
        });
        assertCode(ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE, new Runnable() { public void run() {
            missing.chat(simpleRequest());
        }});
        final AnthropicChatModel malformed = model(new FakeTransport(response(200, "not-json")), secret());
        assertCode(ErrorCodes.MODEL_PROTOCOL_ERROR, new Runnable() { public void run() {
            malformed.chat(simpleRequest());
        }});
        final AnthropicChatModel unknown = model(new FakeTransport(response(200,
                "{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"hidden\"}],"
                + "\"stop_reason\":\"end_turn\"}")), secret());
        assertCode(ErrorCodes.MODEL_PROTOCOL_ERROR, new Runnable() { public void run() {
            unknown.chat(simpleRequest());
        }});
    }

    private void assertFailure(final int status, ErrorCategory category, boolean retryable) {
        final AnthropicChatModel failed = model(new FakeTransport(response(status,
                "secret provider diagnostic")), secret());
        try {
            failed.chat(simpleRequest());
            fail("expected provider failure");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.MODEL_PROVIDER_FAILED, exception.getError().getCode());
            assertEquals(category, exception.getError().getCategory());
            assertEquals(String.valueOf(status), exception.getError().getProviderCode());
            assertEquals(retryable, exception.getError().isRetryable());
            assertFalse(exception.getMessage().contains("diagnostic"));
        }
    }

    private static AnthropicChatModel model(ProviderHttpTransport transport,
            ProviderCredentialResolver resolver) {
        try {
            return new AnthropicChatModel(new AnthropicChatConfig(
                    new URI("https://api.anthropic.example/v1/messages"), "claude-fixed",
                    "messages-v1", "2023-06-01", "secret-ref", 512), transport, resolver);
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static ProviderCredentialResolver secret() {
        return new ProviderCredentialResolver() {
            public char[] resolve(String tenantId, String secretRef) {
                return "tenant".equals(tenantId) && "secret-ref".equals(secretRef)
                        ? "secret-value".toCharArray() : null;
            }
        };
    }

    private static ChatRequest simpleRequest() {
        return new ChatRequest(Collections.singletonList(text(Role.USER, "hello")),
                null, null, null, context());
    }

    private static ChatMessage text(Role role, String value) {
        return new ChatMessage(role, Collections.<Content>singletonList(new TextContent(value)),
                null, null);
    }

    private static InvocationContext context() {
        return new InvocationContext("tenant", null, null, "trace",
                System.currentTimeMillis() + 5000, new CancellationToken());
    }

    private static ProviderHttpResponse response(int status, String body) {
        return new ProviderHttpResponse(status, null, body.getBytes(StandardCharsets.UTF_8));
    }

    private static String textResponse(String text, String reason) {
        return "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\""
                + text + "\"}],\"stop_reason\":\"" + reason + "\"}";
    }

    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException exception) { assertEquals(code, exception.getError().getCode()); }
    }

    private static void assertIllegal(Runnable action) {
        try { action.run(); fail("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }

    private static final class FakeTransport implements ProviderHttpTransport {
        private final ProviderHttpResponse response;
        private ProviderHttpRequest request;
        private FakeTransport(ProviderHttpResponse response) { this.response = response; }
        public ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context) {
            this.request = request;
            return response;
        }
    }
}
