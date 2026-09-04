package com.clara.model.provider.deepseek;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import com.clara.model.api.ModelProvider;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpRequest;
import com.clara.model.provider.http.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeepSeekModelCatalogClientTest {
    @Test public void fetchesSortedImmutableAvailabilitySnapshot() {
        FakeTransport transport = new FakeTransport(response(200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"model-z\",\"object\":\"model\","
                        + "\"owned_by\":\"deepseek\"},{\"id\":\"model-a\","
                        + "\"object\":\"model\",\"owned_by\":\"deepseek\"}]}"));
        DeepSeekModelAvailabilitySnapshot snapshot = client(transport, secret(), 10, 64).fetch(context());

        assertEquals(ModelProvider.DEEPSEEK, snapshot.getProvider());
        assertEquals("models-v1", snapshot.getApiProfile());
        assertEquals(123456L, snapshot.getDiscoveredAtEpochMillis());
        assertEquals("model-a", snapshot.getModels().get(0).getModelId());
        assertEquals("model-z", snapshot.getModels().get(1).getModelId());
        assertTrue(snapshot.contains("model-a"));
        assertFalse(snapshot.contains("missing"));
        assertEquals("GET", transport.request.getMethod());
        assertEquals("Bearer tenant-secret", transport.request.getHeaders().get("Authorization"));
        assertEquals(0, transport.request.getBody().length);
        try {
            snapshot.getModels().clear();
            fail("expected immutable model list");
        } catch (UnsupportedOperationException expected) { }
    }

    @Test public void acceptsEmptyCatalog() {
        DeepSeekModelAvailabilitySnapshot snapshot = client(new FakeTransport(response(200,
                "{\"object\":\"list\",\"data\":[]}")), secret(), 10, 64).fetch(context());
        assertTrue(snapshot.getModels().isEmpty());
    }

    @Test public void rejectsDuplicateMalformedAndOversizedCatalogs() {
        assertProtocol("{\"object\":\"list\",\"data\":["
                + "{\"id\":\"same\",\"object\":\"model\"},"
                + "{\"id\":\"same\",\"object\":\"model\"}]}", 10, 64);
        assertProtocol("{\"object\":\"list\",\"data\":["
                + "{\"id\":\"bad\",\"object\":\"not-model\"}]}", 10, 64);
        assertProtocol("{\"object\":\"list\",\"data\":["
                + "{\"id\":\"toolong\",\"object\":\"model\"}]}", 10, 3);
        final DeepSeekModelCatalogClient limited = client(new FakeTransport(response(200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"a\",\"object\":\"model\"},"
                        + "{\"id\":\"b\",\"object\":\"model\"}]}")), secret(), 1, 64);
        assertCode(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, new Runnable() { public void run() {
            limited.fetch(context());
        }});
    }

    @Test public void mapsHttpFailuresWithoutExposingBody() {
        final DeepSeekModelCatalogClient client = client(new FakeTransport(response(429,
                "secret provider diagnostic")), secret(), 10, 64);
        try {
            client.fetch(context());
            fail("expected provider failure");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.MODEL_PROVIDER_FAILED, exception.getError().getCode());
            assertEquals("429", exception.getError().getProviderCode());
            assertTrue(exception.getError().isRetryable());
            assertFalse(exception.getMessage().contains("diagnostic"));
        }
    }

    @Test public void rejectsMissingCredentialAndInvalidConfiguration() throws Exception {
        final DeepSeekModelCatalogClient missing = client(new FakeTransport(response(200,
                "{\"object\":\"list\",\"data\":[]}")), new ProviderCredentialResolver() {
            public char[] resolve(String tenantId, String secretRef) { return null; }
        }, 10, 64);
        assertCode(ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE, new Runnable() { public void run() {
            missing.fetch(context());
        }});
        try {
            new DeepSeekModelCatalogConfig(new URI("https://api.deepseek.example/models"),
                    "models-v1", "secret-ref", 0, 64);
            fail("expected invalid limits");
        } catch (IllegalArgumentException expected) { }
    }

    private static void assertProtocol(String body, int maxModels, int maxIdChars) {
        final DeepSeekModelCatalogClient client = client(
                new FakeTransport(response(200, body)), secret(), maxModels, maxIdChars);
        assertCode(ErrorCodes.MODEL_PROTOCOL_ERROR, new Runnable() { public void run() {
            client.fetch(context());
        }});
    }

    private static DeepSeekModelCatalogClient client(ProviderHttpTransport transport,
            ProviderCredentialResolver resolver, int maxModels, int maxIdChars) {
        try {
            DeepSeekModelCatalogConfig config = new DeepSeekModelCatalogConfig(
                    new URI("https://api.deepseek.example/models"), "models-v1", "secret-ref",
                    maxModels, maxIdChars);
            return new DeepSeekModelCatalogClient(config, transport, resolver,
                    Clock.fixed(Instant.ofEpochMilli(123456L), ZoneOffset.UTC));
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static ProviderCredentialResolver secret() {
        return new ProviderCredentialResolver() {
            public char[] resolve(String tenantId, String secretRef) {
                return "tenant".equals(tenantId) && "secret-ref".equals(secretRef)
                        ? "tenant-secret".toCharArray() : null;
            }
        };
    }

    private static InvocationContext context() {
        return new InvocationContext("tenant", null, null, "trace",
                System.currentTimeMillis() + 5000, new CancellationToken());
    }

    private static ProviderHttpResponse response(int status, String body) {
        return new ProviderHttpResponse(status, Collections.<String, java.util.List<String>>emptyMap(),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException exception) { assertEquals(code, exception.getError().getCode()); }
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
