package com.clara.model.provider.compatible;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import com.clara.model.api.EmbeddingRequest;
import com.clara.model.api.EmbeddingResponse;
import com.clara.model.api.ModelProvider;
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

public class OpenAiCompatibleEmbeddingModelTest {
    @Test public void mapsBatchAndRestoresProviderIndexOrder() throws Exception {
        FakeTransport transport = new FakeTransport(response(200,
                "{\"data\":[{\"index\":1,\"embedding\":[3.0,4.0]},{\"index\":0,\"embedding\":[1.0,2.0]}]}"));
        OpenAiCompatibleEmbeddingModel model = model(ModelProvider.OPENAI, transport, 2, 3, 10, 20);
        EmbeddingResponse result = model.embed(new EmbeddingRequest(
                Arrays.asList("first", "second"), context()));
        assertArrayEquals(new float[] {1, 2}, result.getEmbeddings().get(0), 0.0f);
        assertArrayEquals(new float[] {3, 4}, result.getEmbeddings().get(1), 0.0f);
        JsonNode sent = new ObjectMapper().readTree(transport.request.getBody());
        assertEquals("fixed-embedding", sent.get("model").asText());
        assertEquals("first", sent.get("input").get(0).asText());
        assertEquals("float", sent.get("encoding_format").asText());
        assertEquals("Bearer credential", transport.request.getHeaders().get("Authorization"));
    }

    @Test public void permitsOnlyConfirmedCompatibleProviders() throws Exception {
        new OpenAiCompatibleEmbeddingConfig(ModelProvider.ALIBABA, new URI("https://example.test/embeddings"),
                "model", "compatible", "secret", 2, 2, 10, 20);
        assertIllegal(new Runnable() { public void run() {
            try {
                new OpenAiCompatibleEmbeddingConfig(ModelProvider.DEEPSEEK,
                        new URI("https://example.test/embeddings"), "model", "compatible",
                        "secret", 2, 2, 10, 20);
            } catch (java.net.URISyntaxException exception) { throw new AssertionError(exception); }
        }});
    }

    @Test public void rejectsBatchTextAndTotalLimitsBeforeResolvingCredential() {
        final int[] resolutions = new int[1];
        ProviderCredentialResolver resolver = new ProviderCredentialResolver() {
            public char[] resolve(String tenantId, String secretRef) {
                resolutions[0]++;
                return "credential".toCharArray();
            }
        };
        final OpenAiCompatibleEmbeddingModel model = model(ModelProvider.OPENAI,
                new FakeTransport(response(200, "{}")), 2, 1, 3, 3, resolver);
        assertCode(ErrorCodes.MODEL_REQUEST_TOO_LARGE, new Runnable() { public void run() {
            model.embed(new EmbeddingRequest(Arrays.asList("a", "b"), context()));
        }});
        assertCode(ErrorCodes.MODEL_REQUEST_TOO_LARGE, new Runnable() { public void run() {
            model.embed(new EmbeddingRequest(Collections.singletonList("long"), context()));
        }});
        assertEquals(0, resolutions[0]);
    }

    @Test public void rejectsMissingDuplicateAndWrongDimensionVectors() {
        assertProtocol("{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}", 2);
        assertProtocol("{\"data\":[{\"index\":0,\"embedding\":[1,2]},{\"index\":0,\"embedding\":[3,4]}]}", 2);
        assertProtocol("{\"data\":[{\"index\":0,\"embedding\":[1]}]}", 1);
        assertProtocol("{\"data\":[{\"index\":0,\"embedding\":[\"bad\",2]}]}", 1);
    }

    @Test public void mapsProviderAndCredentialFailuresSafely() {
        final OpenAiCompatibleEmbeddingModel limited = model(ModelProvider.ALIBABA,
                new FakeTransport(response(503, "private diagnostic")), 2, 2, 10, 20);
        try {
            limited.embed(new EmbeddingRequest(Collections.singletonList("x"), context()));
            fail("expected provider failure");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.MODEL_PROVIDER_FAILED, exception.getError().getCode());
            assertEquals("503", exception.getError().getProviderCode());
            assertTrue(exception.getError().isRetryable());
            assertFalse(exception.getMessage().contains("diagnostic"));
        }
        final OpenAiCompatibleEmbeddingModel missing = model(ModelProvider.OPENAI,
                new FakeTransport(response(200, "{}")), 2, 2, 10, 20,
                new ProviderCredentialResolver() {
                    public char[] resolve(String tenantId, String secretRef) { return null; }
                });
        assertCode(ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE, new Runnable() { public void run() {
            missing.embed(new EmbeddingRequest(Collections.singletonList("x"), context()));
        }});
    }

    private static void assertProtocol(String response, int inputs) {
        final OpenAiCompatibleEmbeddingModel model = model(ModelProvider.OPENAI,
                new FakeTransport(response(200, response)), 2, 3, 10, 20);
        final java.util.List<String> texts = inputs == 1 ? Collections.singletonList("x")
                : Arrays.asList("x", "y");
        assertCode(ErrorCodes.MODEL_PROTOCOL_ERROR, new Runnable() { public void run() {
            model.embed(new EmbeddingRequest(texts, context()));
        }});
    }

    private static OpenAiCompatibleEmbeddingModel model(ModelProvider provider,
            ProviderHttpTransport transport, int dimension, int maxInputs,
            int maxText, int maxTotal) {
        return model(provider, transport, dimension, maxInputs, maxText, maxTotal,
                new ProviderCredentialResolver() {
                    public char[] resolve(String tenantId, String secretRef) {
                        return "credential".toCharArray();
                    }
                });
    }

    private static OpenAiCompatibleEmbeddingModel model(ModelProvider provider,
            ProviderHttpTransport transport, int dimension, int maxInputs,
            int maxText, int maxTotal, ProviderCredentialResolver resolver) {
        try {
            return new OpenAiCompatibleEmbeddingModel(new OpenAiCompatibleEmbeddingConfig(provider,
                    new URI("https://provider.example/v1/embeddings"), "fixed-embedding",
                    "embeddings-v1", "secret-ref", dimension, maxInputs, maxText, maxTotal),
                    transport, resolver);
        } catch (Exception exception) { throw new AssertionError(exception); }
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
        public ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context,
                ProviderSseListener listener) {
            throw new UnsupportedOperationException("streaming is not used in this test");
        }
    }
}
