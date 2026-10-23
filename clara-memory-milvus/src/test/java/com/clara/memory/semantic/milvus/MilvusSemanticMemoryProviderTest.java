package com.clara.memory.semantic.milvus;

import com.clara.memory.semantic.spi.ISemanticMemoryBackendProvider;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Milvus 后端提供者：未配置时必须安静地回退，且能被 {@link ServiceLoader} 发现。
 */
public class MilvusSemanticMemoryProviderTest {

    @Test
    public void returnsNullWhenConfigMissing() {
        MilvusSemanticMemoryProvider provider = new MilvusSemanticMemoryProvider();
        assertNull(provider.open(null, 1024, "demo"));
    }

    @Test
    public void returnsNullWhenDisabled() throws Exception {
        Path config = Files.createTempFile("clara-milvus", ".yml");
        Files.write(config, "milvus:\n  enabled: false\n".getBytes(StandardCharsets.UTF_8));
        try {
            assertNull(new MilvusSemanticMemoryProvider().open(config, 1024, "demo"));
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    public void discoveredByServiceLoader() {
        boolean found = false;
        for (ISemanticMemoryBackendProvider provider : ServiceLoader.load(ISemanticMemoryBackendProvider.class)) {
            found = found || provider instanceof MilvusSemanticMemoryProvider;
        }
        assertTrue("ServiceLoader 未发现 Milvus 提供者", found);
        assertNotNull(new MilvusSemanticMemoryProvider());
    }
}
