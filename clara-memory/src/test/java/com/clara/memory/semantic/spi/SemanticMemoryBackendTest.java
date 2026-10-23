package com.clara.memory.semantic.spi;

import com.clara.memory.semantic.local.LocalSemanticMemoryStore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * 后端描述对象的构造校验与取值。
 */
public class SemanticMemoryBackendTest {

    @Test
    public void exposesStoreAndDescription() {
        LocalSemanticMemoryStore store = new LocalSemanticMemoryStore(8);
        SemanticMemoryBackend backend = new SemanticMemoryBackend(store, "milvus demo");
        assertSame(store, backend.getStore());
        assertEquals("milvus demo", backend.getDescription());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullStore() {
        new SemanticMemoryBackend(null, "demo");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullDescription() {
        new SemanticMemoryBackend(new LocalSemanticMemoryStore(8), null);
    }
}
