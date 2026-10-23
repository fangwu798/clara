package com.clara.memory.semantic.milvus;

import com.clara.memory.semantic.spi.SemanticMemoryBackend;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertNull;

/**
 * {@link MilvusSemanticMemoryProvider} 在「已配置但连不上」时的降级行为。
 *
 * <p>为什么要测失败路径：Milvus 配了却没起服务是部署初期的常态。此时必须静默降级为进程内实现，
 * 整个应用不能因为向量库不可用而起不来；而工厂选型、集合规格推导、资源释放这些分支
 * 只有真正走一次连接才会被执行到。
 */
public class MilvusSemanticMemoryProviderEdgeTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void unreachableVectorMilvusFallsBackToNull() throws Exception {
        Path config = write("milvus:\n"
                + "  enabled: true\n"
                + "  host: 127.0.0.1\n"
                + "  port: 1\n"
                + "  collection: clara_test_conversations\n"
                + "  connectTimeoutMillis: 200\n");
        assertNull("连不上时应降级而不是抛出", new MilvusSemanticMemoryProvider().open(config, 3, "demo"));
    }

    @Test
    public void unreachableBm25MilvusFallsBackToNull() throws Exception {
        Path config = write("milvus:\n"
                + "  enabled: true\n"
                + "  mode: bm25\n"
                + "  host: 127.0.0.1\n"
                + "  port: 1\n"
                + "  collection: clara_test_conversations\n"
                + "  analyzer: chinese\n");
        assertNull("BM25 模式连不上时同样应降级", new MilvusSemanticMemoryProvider().open(config, 3, "demo"));
    }

    @Test
    public void invalidConfigIsReportedAsUnavailable() throws Exception {
        Path config = write("milvus:\n  enabled: true\n  port: 0\n");
        assertNull("非法配置应降级为进程内实现", new MilvusSemanticMemoryProvider().open(config, 3, "demo"));
    }

    private Path write(String yaml) throws Exception {
        Path file = folder.newFile("clara.yml").toPath();
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}