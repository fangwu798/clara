package com.clara.kernel.config;

import com.clara.enums.ProtocolEnum;
import org.junit.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Capability configuration validation, protocol derivation and typed parameter readers.
 */
public class ProviderCapabilityConfigurationTest {

    private static final URI ENDPOINT = URI.create("https://api.example.com/v1/chat");

    @Test
    public void rejectsInvalidEndpoints() {
        assertIllegalArgument(() -> capability(null, null, null, null, null, null, null));
        assertIllegalArgument(() -> capability(URI.create("/relative"), null, null, null, null, null, null));
        assertIllegalArgument(() -> capability(ENDPOINT, URI.create("/relative"), null, null, null, null, null));
    }

    @Test
    public void derivesProtocolFromApiProfile() {
        assertEquals(ProtocolEnum.RESPONSE,
                capability(ENDPOINT, null, null, "codex-response", null, null, null).getProtocol());
        assertEquals(ProtocolEnum.COMPLETION,
                capability(ENDPOINT, null, null, "openai-completion", null, null, null).getProtocol());
        assertEquals(ProtocolEnum.COMPLETION,
                capability(ENDPOINT, null, null, null, null, null, null).getProtocol());
        assertEquals(ProtocolEnum.COMPLETION,
                capability(ENDPOINT, null, null, null, ProtocolEnum.COMPLETION, null, null).getProtocol());
    }

    @Test
    public void normalisesBlankOptionalFields() {
        ProviderCapabilityConfiguration configuration = capability(ENDPOINT, null, "  ", " ", null, null, null);
        assertNull(configuration.getModelId());
        assertNull(configuration.getApiProfile());
        assertTrue(configuration.getParameters().isEmpty());
    }

    @Test
    public void requiresModelIdAndApiProfile() {
        ProviderCapabilityConfiguration configuration = capability(ENDPOINT, null, "m-1", "p-1", null, null, null);
        assertEquals("m-1", configuration.requireModelId());
        assertEquals("p-1", configuration.requireApiProfile());
        assertIllegalArgument(() -> capability(ENDPOINT, null, null, null, null, null, null).requireModelId());
        assertIllegalArgument(() -> capability(ENDPOINT, null, null, null, null, null, null).requireApiProfile());
    }

    @Test
    public void requiresTaskQueryEndpoint() {
        ProviderCapabilityConfiguration configuration = capability(ENDPOINT,
                URI.create("https://api.example.com/v1/tasks"), null, null, null, null, null);
        assertEquals(URI.create("https://api.example.com/v1/tasks"), configuration.requireTaskQueryEndpoint());
        assertIllegalArgument(() -> capability(ENDPOINT, null, null, null, null, null, null)
                .requireTaskQueryEndpoint());
    }

    @Test
    public void readsTypedParameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("count", 12);
        parameters.put("ratio", 2.0d);
        parameters.put("flag", Boolean.TRUE);
        parameters.put("name", "value");
        ProviderCapabilityConfiguration configuration = capability(ENDPOINT, null, null, null, null, parameters, null);

        assertEquals(12, configuration.requireInt("count"));
        assertEquals(2, configuration.requireInt("ratio"));
        assertTrue(configuration.requireBoolean("flag"));
        assertEquals("value", configuration.requireString("name"));
    }

    @Test
    public void rejectsParametersOfWrongTypeOrMissing() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("text", "abc");
        parameters.put("fraction", 2.5d);
        parameters.put("flag", "true");
        parameters.put("blank", "  ");
        ProviderCapabilityConfiguration configuration = capability(ENDPOINT, null, null, null, null, parameters, null);

        assertIllegalArgument(() -> configuration.requireInt("text"));
        assertIllegalArgument(() -> configuration.requireInt("fraction"));
        assertIllegalArgument(() -> configuration.requireInt("missing"));
        assertIllegalArgument(() -> configuration.requireBoolean("flag"));
        assertIllegalArgument(() -> configuration.requireBoolean("missing"));
        assertIllegalArgument(() -> configuration.requireString("blank"));
        assertIllegalArgument(() -> configuration.requireString("missing"));
    }

    @Test
    public void returnsDefaultContextControlWhenNotConfigured() {
        ProviderCapabilityConfiguration configuration = capability(ENDPOINT, null, null, null, null, null, null);
        assertEquals(-1L, configuration.contextControlConfig().getContextWindowTokens());

        ContextControlConfiguration control = new ContextControlConfiguration(
                100L, 5, 50L, 40, null, Boolean.TRUE, Boolean.FALSE, 2, 0.9, 100);
        ProviderCapabilityConfiguration configured = capability(ENDPOINT, null, null, null, null, null, control);
        assertEquals(100L, configured.contextControlConfig().getContextWindowTokens());
        assertEquals(2, configured.contextControlConfig().getRetrievalCount());
    }

    private static ProviderCapabilityConfiguration capability(URI endpoint, URI taskQuery, String modelId,
            String apiProfile, ProtocolEnum protocol, Map<String, Object> parameters,
            ContextControlConfiguration control) {
        return new ProviderCapabilityConfiguration(endpoint, taskQuery, modelId, apiProfile, protocol,
                parameters, control);
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
