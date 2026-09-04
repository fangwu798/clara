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

public class OpenAiCompatibleChatModelTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test public void mapsConfiguredModelMessagesToolsAndParameters() throws Exception {
        FakeTransport transport = new FakeTransport(response(200,
                "{\"id\":\"r1\",\"model\":\"fixed-model\",\"choices\":[{\"message\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}"));
        OpenAiCompatibleChatModel model = model(ModelProvider.DEEPSEEK, transport, secret());
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        ToolSpecification tool = new ToolSpecification("lookup", "Lookup data", schema);
        ModelParameters parameters = new ModelParameters("fixed-model", Double.valueOf(0.2),
                Double.valueOf(0.8), null, Integer.valueOf(50), Double.valueOf(0.1),
                Double.valueOf(0.0), "json_object", Collections.singletonList("END"), false);
        ChatRequest request = new ChatRequest(Collections.singletonList(text(Role.USER, "hello")),
                parameters, Collections.singletonList(tool), null, context());

        ChatResponse result = model.chat(request);
        JsonNode sent = json.readTree(transport.request.getBody());
        assertEquals("fixed-model", sent.get("model").asText());
        assertEquals("hello", sent.get("messages").get(0).get("content").asText());
        assertEquals("lookup", sent.get("tools").get(0).get("function").get("name").asText());
        assertEquals(50, sent.get("max_tokens").asInt());
        assertEquals("json_object", sent.get("response_format").get("type").asText());
        assertEquals("Bearer secret-value", transport.request.getHeaders().get("Authorization"));
        assertEquals("done", ((TextContent) result.getMessage().getContents().get(0)).getText());
        assertEquals(FinishReason.STOP, result.getFinishReason());
        assertEquals(5, result.getTokenUsage().getTotalTokens());
        assertEquals("DEEPSEEK", result.getProviderMetadata().get("provider"));
    }

    @Test public void mapsAssistantToolCallsInBothDirections() throws Exception {
        FakeTransport transport = new FakeTransport(response(200,
                "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"call-2\",\"type\":\"function\","
                + "\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"HZ\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}"));
        OpenAiCompatibleChatModel model = model(ModelProvider.OPENAI, transport, secret());
        List<Content> contents = Arrays.<Content>asList(new TextContent("checking"),
                new ToolCallContent("call-1", "lookup", "{\"id\":1}"));
        ChatRequest request = new ChatRequest(Collections.singletonList(
                new ChatMessage(Role.ASSISTANT, contents, null, null)), null, null, null, context());
        ChatResponse response = model.chat(request);

        JsonNode sentCall = json.readTree(transport.request.getBody()).get("messages").get(0)
                .get("tool_calls").get(0);
        assertEquals("call-1", sentCall.get("id").asText());
        ToolCallContent received = (ToolCallContent) response.getMessage().getContents().get(0);
        assertEquals("call-2", received.getId());
        assertEquals("weather", received.getName());
        assertEquals(FinishReason.TOOL_CALLS, response.getFinishReason());
    }

    @Test public void mapsHttpFailuresWithoutExposingResponseBody() {
        final OpenAiCompatibleChatModel limited = model(ModelProvider.ALIBABA,
                new FakeTransport(response(429, "secret provider diagnostic")), secret());
        try {
            limited.chat(simpleRequest());
            fail("expected provider failure");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.MODEL_PROVIDER_FAILED, exception.getError().getCode());
            assertEquals("429", exception.getError().getProviderCode());
            assertTrue(exception.getError().isRetryable());
            assertFalse(exception.getMessage().contains("diagnostic"));
        }
        final OpenAiCompatibleChatModel malformed = model(ModelProvider.VOLCENGINE,
                new FakeTransport(response(200, "{\"choices\":[]}")), secret());
        assertCode(ErrorCodes.MODEL_PROTOCOL_ERROR, new Runnable() { public void run() {
            malformed.chat(simpleRequest());
        }});
    }

    @Test public void rejectsModelBypassUnsupportedFieldsAndMissingCredential() {
        final OpenAiCompatibleChatModel model = model(ModelProvider.OPENAI,
                new FakeTransport(response(200, "{}")), secret());
        ModelParameters wrongModel = new ModelParameters("other", null, null, null, null, false);
        assertIllegal(new Runnable() { public void run() {
            model.chat(new ChatRequest(Collections.singletonList(text(Role.USER, "x")),
                    wrongModel, null, null, context()));
        }});
        final ModelParameters topK = new ModelParameters(null, null, null, Integer.valueOf(5),
                null, null, null, null, null, false);
        assertCode(ErrorCodes.CAPABILITY_NOT_SUPPORTED, new Runnable() { public void run() {
            model.chat(new ChatRequest(Collections.singletonList(text(Role.USER, "x")),
                    topK, null, null, context()));
        }});
        final ChatMessage media = new ChatMessage(Role.USER,
                Collections.<Content>singletonList(new MediaContent("image", "asset")), null, null);
        assertCode(ErrorCodes.CAPABILITY_NOT_SUPPORTED, new Runnable() { public void run() {
            model.chat(new ChatRequest(Collections.singletonList(media), null, null, null, context()));
        }});
        final OpenAiCompatibleChatModel missing = model(ModelProvider.OPENAI,
                new FakeTransport(response(200, "{}")), new ProviderCredentialResolver() {
                    public char[] resolve(String tenantId, String secretRef) { return null; }
                });
        assertCode(ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE, new Runnable() { public void run() {
            missing.chat(simpleRequest());
        }});
    }

    @Test public void keepsProviderOptionsIsolated() {
        final OpenAiCompatibleChatModel model = model(ModelProvider.DEEPSEEK,
                new FakeTransport(response(200, "{}")), secret());
        ProviderOptions wrong = new ProviderOptions() {
            public ModelProvider getProvider() { return ModelProvider.OPENAI; }
        };
        final ChatRequest request = new ChatRequest(Collections.singletonList(text(Role.USER, "x")),
                null, null, wrong, context());
        assertIllegal(new Runnable() { public void run() { model.chat(request); }});
    }

    private static OpenAiCompatibleChatModel model(ModelProvider provider,
            ProviderHttpTransport transport, ProviderCredentialResolver resolver) {
        try {
            return new OpenAiCompatibleChatModel(new OpenAiCompatibleChatConfig(provider,
                    new URI("https://provider.example/v1/chat/completions"), "fixed-model",
                    "chat-completions-v1", "secret-ref"), transport, resolver);
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
        return new ChatMessage(role, Collections.<Content>singletonList(new TextContent(value)), null, null);
    }

    private static InvocationContext context() {
        return new InvocationContext("tenant", null, null, "trace",
                System.currentTimeMillis() + 5000, new CancellationToken());
    }

    private static ProviderHttpResponse response(int status, String body) {
        return new ProviderHttpResponse(status, null, body.getBytes(StandardCharsets.UTF_8));
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
