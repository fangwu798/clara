package com.clara.model.embedding;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Deterministic local embedding used by offline tests.
 */
public class HashingEmbeddingClientTest {

    @Test
    public void producesSameVectorForSameTextAndUnitLength() {
        HashingEmbeddingClient client = new HashingEmbeddingClient(16);

        float[] first = client.embed("abc");
        float[] second = client.embed("abc");
        float[] other = client.embed("xyz");

        assertArrayEquals(first, second, 0.0f);
        assertEquals(16, first.length);
        assertEquals(16, other.length);
        double length = 0.0;
        for (float value : first) {
            length += value * value;
        }
        assertEquals(1.0, Math.sqrt(length), 0.0001);
    }

    @Test
    public void defaultDimensionIs1024() {
        assertEquals(1024, new HashingEmbeddingClient().embed("a").length);
    }

    @Test
    public void rejectsInvalidDimensionAndNullText() {
        try {
            new HashingEmbeddingClient(0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new HashingEmbeddingClient(-1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new HashingEmbeddingClient(4).embed(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
