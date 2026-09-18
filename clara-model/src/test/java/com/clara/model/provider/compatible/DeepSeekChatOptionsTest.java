package com.clara.model.provider.compatible;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.InvocationContext;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.Content;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.ProviderOptions;
import com.clara.model.api.Role;
import com.clara.model.api.TextContent;
import com.clara.model.provider.compatible.config.OpenAiCompatibleChatConfig;
import com.clara.model.provider.deepseek.DeepSeekChatOptions;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.impl.ProviderHttpRequest;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.ProviderSseListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeepSeekChatOptionsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test public void mapsPrefixAndReasoningOnlyToFinalAssistant() throws Exception {
        FakeTransport transport = new FakeTransport();
        ChatRequest request = new ChatRequest(Arrays.asList(text(Role.USER, "question"),
                text(Role.ASSISTANT, "prefix")), null, null,
                new DeepSeekChatOptions(Boolean.TRUE, "prior reasoning"), context());

        ChatResponse response = model(ModelProvider.DEEPSEEK, transport).chat(request);
        JsonNode messages = json.readTree(transport.request.getBody()).get("messages");
        assertFalse(messages.get(0).has("prefix"));
        assertTrue(messages.get(1).get("prefix").asBoolean());
        assertEquals("prior reasoning", messages.get(1).get("reasoning_content").asText());
        assertEquals("response reasoning", response.getProviderMetadata().get("reasoning"));
    }

    @Test public void rejectsPrefixWhenFinalMessageIsNotAssistant() {
        final OpenAiCompatibleChatModel model = model(ModelProvider.DEEPSEEK, new FakeTransport());
        final ChatRequest request = new ChatRequest(Collections.singletonList(text(Role.USER, "x")),
                null, null, new DeepSeekChatOptions(Boolean.TRUE, null), context());
        assertIllegal(new Runnable() { public void run() { model.chat(request); }});
    }

    @Test public void rejectsUnknownSameProviderOptionsAndCrossProviderUse() {
        final ProviderOptions unknown = new ProviderOptions() {
            public ModelProvider getProvider() { return ModelProvider.DEEPSEEK; }
        };
        final OpenAiCompatibleChatModel deepSeek = model(ModelProvider.DEEPSEEK, new FakeTransport());
        final ChatRequest unknownRequest = new ChatRequest(
                Collections.singletonList(text(Role.USER, "x")), null, null, unknown, context());
        assertIllegal(new Runnable() { public void run() { deepSeek.chat(unknownRequest); }});

        final OpenAiCompatibleChatModel openAi = model(ModelProvider.OPENAI, new FakeTransport());
        final ChatRequest crossProvider = new ChatRequest(
                Collections.singletonList(text(Role.ASSISTANT, "x")), null, null,
                new DeepSeekChatOptions(Boolean.TRUE, null), context());
        assertIllegal(new Runnable() { public void run() { openAi.chat(crossProvider); }});
    }

    @Test public void validatesReasoningRequiresEnabledPrefix() {
        assertInvalidOptions(Boolean.FALSE);
        assertInvalidOptions(null);
    }

    private static void assertInvalidOptions(Boolean prefix) {
        try {
            new DeepSeekChatOptions(prefix, "reasoning");
            fail("expected invalid options");
        } catch (IllegalArgumentException expected) { }
    }

    private static OpenAiCompatibleChatModel model(ModelProvider provider,
            ProviderHttpTransport transport) {
        try {
            return new OpenAiCompatibleChatModel(new OpenAiCompatibleChatConfig(provider,
                    new URI("https://provider.example/v1/chat/completions"), "fixed-model",
                    "chat-completions-v1", "secret-ref"), transport,
                    new ProviderCredentialResolver() {
                        public char[] resolve(String tenantId, String secretRef) {
                            return "secret".toCharArray();
                        }
                    });
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static ChatMessage text(Role role, String text) {
        return new ChatMessage(role, Collections.<Content>singletonList(new TextContent(text)),
                null, null);
    }

    private static InvocationContext context() {
        return new InvocationContext("tenant", null, null, "trace",
                System.currentTimeMillis() + 5000, new CancellationToken());
    }

    private static void assertIllegal(Runnable action) {
        try { action.run(); fail("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }

    private static final class FakeTransport implements ProviderHttpTransport {
        private ProviderHttpRequest request;
        public ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context) {
            this.request = request;
            String body = "{\"id\":\"r1\",\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"answer\",\"reasoning_content\":\"response reasoning\"},"
                    + "\"finish_reason\":\"stop\"}]}";
            return new ProviderHttpResponse(200, null, body.getBytes(StandardCharsets.UTF_8));
        }
        public ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context,
                ProviderSseListener listener) {
            throw new UnsupportedOperationException("streaming is not used in this test");
        }
    }
}
