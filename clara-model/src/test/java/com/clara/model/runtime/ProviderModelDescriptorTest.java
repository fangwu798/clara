package com.clara.model.runtime;

import com.clara.model.api.ModelCapability;
import com.clara.model.api.ModelProvider;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProviderModelDescriptorTest {
    @Test
    public void oldConstructorUsesEmptyMetadata() {
        ProviderModelDescriptor descriptor = descriptor(EnumSet.of(ModelCapability.CHAT_TEXT));

        assertSame(ProviderModelMetadata.empty(), descriptor.getMetadata());
        assertTrue(descriptor.getMetadata().getInputModalities().isEmpty());
    }

    @Test
    public void metadataDefensivelyCopiesCollections() {
        Set<String> parameters = new LinkedHashSet<String>();
        parameters.add("temperature");
        ProviderModelMetadata metadata = ProviderModelMetadata.builder()
                .inputModalities(EnumSet.of(ModelModality.TEXT, ModelModality.IMAGE))
                .outputModalities(EnumSet.of(ModelModality.TEXT))
                .contextWindowTokens(Integer.valueOf(128000))
                .maxOutputTokens(Integer.valueOf(8192))
                .supportedParameters(parameters)
                .executionModes(EnumSet.of(ModelExecutionMode.SYNCHRONOUS, ModelExecutionMode.STREAMING))
                .dataResidencyLabels(Collections.singleton("global"))
                .pricingVersion("2026-09")
                .releasedAt(Long.valueOf(100L))
                .deprecatedAt(Long.valueOf(200L))
                .discoveredAt(Long.valueOf(150L))
                .sourceRevision("catalog-etag")
                .source(ModelMetadataSource.PROVIDER_CATALOG)
                .build();
        parameters.add("topP");

        assertEquals(Collections.singleton("temperature"), metadata.getSupportedParameters());
        assertEquals(Integer.valueOf(128000), metadata.getContextWindowTokens());
        assertEquals(ModelMetadataSource.PROVIDER_CATALOG, metadata.getSource());
        try {
            metadata.getSupportedParameters().add("seed");
            fail("expected immutable set");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void validatesLimitsAndLifecycle() {
        assertInvalid(ProviderModelMetadata.builder().contextWindowTokens(Integer.valueOf(0)));
        assertInvalid(ProviderModelMetadata.builder().maxOutputTokens(Integer.valueOf(-1)));
        assertInvalid(ProviderModelMetadata.builder()
                .releasedAt(Long.valueOf(200L)).deprecatedAt(Long.valueOf(100L)));
    }

    @Test
    public void catalogMetadataDoesNotGrantCapabilities() {
        ProviderModelMetadata metadata = ProviderModelMetadata.builder()
                .inputModalities(EnumSet.of(ModelModality.IMAGE))
                .source(ModelMetadataSource.PROVIDER_CATALOG)
                .build();
        ProviderModelDescriptor descriptor = new ProviderModelDescriptor(ModelProvider.DEEPSEEK,
                "discovered-id", "catalog-model", "chat", "global", "v1",
                Collections.<ModelCapability>emptySet(), metadata);

        assertFalse(descriptor.supports(ModelCapability.CHAT_IMAGE_INPUT));
        assertTrue(descriptor.getMetadata().getInputModalities().contains(ModelModality.IMAGE));
    }

    @Test
    public void metadataSourcePriorityMatchesArchitecturePrecedence() {
        assertTrue(ModelMetadataSource.CONTRACT_TEST.getPriority()
                > ModelMetadataSource.STARTUP_PROBE.getPriority());
        assertTrue(ModelMetadataSource.STARTUP_PROBE.getPriority()
                > ModelMetadataSource.AUDITED_STATIC.getPriority());
        assertTrue(ModelMetadataSource.AUDITED_STATIC.getPriority()
                > ModelMetadataSource.PROVIDER_CATALOG.getPriority());
    }

    private static ProviderModelDescriptor descriptor(Set<ModelCapability> capabilities) {
        return new ProviderModelDescriptor(ModelProvider.OPENAI, "model", "alias",
                "chat", "global", "v1", capabilities);
    }

    private static void assertInvalid(ProviderModelMetadata.Builder builder) {
        try {
            builder.build();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
