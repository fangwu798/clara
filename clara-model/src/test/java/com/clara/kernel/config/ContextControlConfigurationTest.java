package com.clara.kernel.config;

import com.clara.dto.memory.ContextControlConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Defaults and validation rules of the context control configuration.
 */
public class ContextControlConfigurationTest {

    @Test
    public void fillsDefaultsForMissingValues() {
        ContextControlConfiguration configuration = new ContextControlConfiguration(
                null, null, null, null, null, null, null, null, null);

        assertEquals(-1L, configuration.getContextWindowTokens());
        assertEquals(-1, configuration.getWindowMaxMessages());
        assertEquals(-1L, configuration.getWindowMaxSizeBytes());
        assertEquals(80, configuration.getWindowMaxTokensPercent());
        assertFalse(configuration.isCompressEnabled());
        assertFalse(configuration.isVectorizeEnabled());
        assertEquals(3, configuration.getRetrievalCount());
        assertEquals(0.7, configuration.getSimilarityThreshold(), 0.0001);
        assertEquals(ContextControlConfig.DEFAULT_TOOL_OUTPUT_MAX_CHARS, configuration.getToolOutputMaxChars());
    }

    @Test
    public void keepsExplicitValuesAndConvertsToDto() {
        ContextControlConfiguration configuration = new ContextControlConfiguration(
                64000L, 20, 1024L, 60, Boolean.TRUE, Boolean.TRUE, 7, 0.5, 900);

        assertEquals(64000L, configuration.getContextWindowTokens());
        assertEquals(20, configuration.getWindowMaxMessages());
        assertEquals(1024L, configuration.getWindowMaxSizeBytes());
        assertEquals(60, configuration.getWindowMaxTokensPercent());
        assertTrue(configuration.isCompressEnabled());
        assertTrue(configuration.isVectorizeEnabled());
        assertEquals(7, configuration.getRetrievalCount());
        assertEquals(0.5, configuration.getSimilarityThreshold(), 0.0001);
        assertEquals(900, configuration.getToolOutputMaxChars());

        ContextControlConfig dto = configuration.toContextControlConfig();
        assertEquals(64000L, dto.getContextWindowTokens());
        assertEquals(20, dto.getWindowMaxMessages());
        assertEquals(1024L, dto.getWindowMaxSizeBytes());
        assertEquals(60, dto.getWindowMaxTokensPercent());
        assertTrue(dto.isCompressEnabled());
        assertTrue(dto.isVectorizeEnabled());
        assertEquals(7, dto.getRetrievalCount());
        assertEquals(0.5, dto.getSimilarityThreshold(), 0.0001);
        assertEquals(900, dto.getToolOutputMaxChars());
    }

    @Test
    public void acceptsBoundaryValues() {
        ContextControlConfiguration configuration = new ContextControlConfiguration(
                -1L, -1, 0L, 0, Boolean.FALSE, Boolean.FALSE, 0, 0.0, -1);
        assertEquals(-1L, configuration.getContextWindowTokens());
        assertEquals(0, configuration.getWindowMaxTokensPercent());
        assertEquals(0.0, configuration.getSimilarityThreshold(), 0.0);
        assertEquals(-1, configuration.getToolOutputMaxChars());

        ContextControlConfiguration upper = new ContextControlConfiguration(
                1L, 1, -1L, 100, null, null, 1, 1.0, 1);
        assertEquals(1L, upper.getContextWindowTokens());
        assertEquals(100, upper.getWindowMaxTokensPercent());
        assertEquals(1.0, upper.getSimilarityThreshold(), 0.0);
    }

    @Test
    public void rejectsInvalidWindowTokens() {
        assertRejected(-2L, null, null, null, null, null, null, null, null);
    }

    @Test
    public void rejectsInvalidWindowMessages() {
        assertRejected(null, -2, null, null, null, null, null, null, null);
    }

    @Test
    public void rejectsInvalidWindowSize() {
        assertRejected(null, null, -2L, null, null, null, null, null, null);
    }

    @Test
    public void rejectsInvalidPercent() {
        assertRejected(null, null, null, 101, null, null, null, null, null);
        assertRejected(null, null, null, -1, null, null, null, null, null);
    }

    @Test
    public void rejectsInvalidRetrievalCount() {
        assertRejected(null, null, null, null, null, null, -1, null, null);
    }

    @Test
    public void rejectsInvalidSimilarityThreshold() {
        assertRejected(null, null, null, null, null, null, null, 1.1, null);
        assertRejected(null, null, null, null, null, null, null, -0.1, null);
        assertRejected(null, null, null, null, null, null, null, Double.NaN, null);
    }

    @Test
    public void rejectsInvalidToolOutputMaxChars() {
        assertRejected(null, null, null, null, null, null, null, null, -2);
    }

    private static void assertRejected(Long windowTokens, Integer maxMessages, Long maxSize, Integer percent,
            Boolean compress, Boolean vectorize, Integer retrieval, Double threshold, Integer toolOutput) {
        try {
            new ContextControlConfiguration(windowTokens, maxMessages, maxSize, percent, compress, vectorize,
                    retrieval, threshold, toolOutput);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
