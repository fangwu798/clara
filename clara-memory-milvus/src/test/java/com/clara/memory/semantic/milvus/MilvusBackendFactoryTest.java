package com.clara.memory.semantic.milvus;

import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link MilvusBackendFactory} 与 {@link MilvusBackendException} 的单元测试。
 *
 * <p>为什么注入后端构造器而不直连实例：工厂的职责只是把集合规格翻译成对应 SDK 客户端——
 * bm25 模式用 v2 客户端、向量模式用 v1 客户端，两者不可互换；而 Milvus SDK 客户端在构造期
 * 就会发起 gRPC 握手，CI 上没有 Milvus 实例时会一直等到连接超时后失败。这里用桩函数替换
 * 「建客户端」这一步，只钉死选型规则；真实客户端的联测由 {@code *IntegrationTest}
 * 在 Milvus 可达时执行。
 */
public class MilvusBackendFactoryTest {

    private final List<String> calls = new ArrayList<>();
    private final MilvusBackend bm25Backend = new StubBackend();
    private final MilvusBackend grpcBackend = new StubBackend();

    @Test
    public void rejectsNullConfig() {
        try {
            MilvusBackendFactory.create(null);
            fail("配置为空应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
        try {
            MilvusBackendFactory.create(null, new MilvusCollectionSpec("c", 3), this::bm25, this::grpc);
            fail("配置为空应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
    }

    @Test
    public void createsVectorBackendForVectorSpec() {
        MilvusBackend backend = MilvusBackendFactory.create(
                config(), new MilvusCollectionSpec("c", 3), this::bm25, this::grpc);

        assertSame("向量模式应使用 gRPC 后端", grpcBackend, backend);
        assertEquals(Collections.singletonList("grpc"), calls);
    }

    @Test
    public void createsVectorBackendWhenSpecMissing() {
        MilvusBackend backend = MilvusBackendFactory.create(config(), null, this::bm25, this::grpc);

        assertSame("缺省规格等同向量模式", grpcBackend, backend);
        assertEquals(Collections.singletonList("grpc"), calls);
    }

    @Test
    public void createsBm25BackendWithCredentials() {
        MilvusBackend backend = MilvusBackendFactory.create(
                new MilvusBackendConfig("127.0.0.1", 19530, "root", null, MilvusBackendConfig.DEFAULT_DATABASE,
                        MilvusBackendConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                        MilvusBackendConfig.DEFAULT_IDLE_TIMEOUT_MILLIS),
                new MilvusCollectionSpec("c", "chinese"), this::bm25, this::grpc);

        assertSame("bm25 模式应使用 v2 客户端后端", bm25Backend, backend);
        assertEquals(Collections.singletonList("bm25"), calls);
        assertFalse("bm25 分支不应同时走向量后端", calls.contains("grpc"));
    }

    @Test
    public void exceptionCarriesRetryableFlagAndCause() {
        MilvusBackendException plain = new MilvusBackendException("boom", true);
        assertEquals("boom", plain.getMessage());
        assertTrue(plain.isRetryable());

        IllegalStateException cause = new IllegalStateException("root");
        MilvusBackendException wrapped = new MilvusBackendException("wrap", false, cause);
        assertEquals("wrap", wrapped.getMessage());
        assertFalse(wrapped.isRetryable());
        assertEquals(cause, wrapped.getCause());
    }

    private static MilvusBackendConfig config() {
        return new MilvusBackendConfig("127.0.0.1", 19530);
    }

    private MilvusBackend bm25(MilvusBackendConfig ignored) {
        calls.add("bm25");
        return bm25Backend;
    }

    private MilvusBackend grpc(MilvusBackendConfig ignored) {
        calls.add("grpc");
        return grpcBackend;
    }

    /**
     * 后端桩实现：只用于断言工厂选中了哪个分支，不参与任何真实调用。
     */
    private static final class StubBackend implements MilvusBackend {

        @Override
        public MilvusCollectionSpec ensureCollection(MilvusCollectionSpec expected) {
            return expected;
        }

        @Override
        public void upsert(String collection, List<MemoryEntry> entries) {
            // 桩实现：不执行任何动作
        }

        @Override
        public List<MemoryMatch> search(String collection, MilvusBackendQuery query) {
            return Collections.emptyList();
        }

        @Override
        public void deleteById(String collection, String entryId) {
            // 桩实现：不执行任何动作
        }

        @Override
        public void deleteByConversation(String collection, String conversationId) {
            // 桩实现：不执行任何动作
        }

        @Override
        public void dropCollection(String collection) {
            // 桩实现：不执行任何动作
        }

        @Override
        public void close() {
            // 桩实现：不执行任何动作
        }
    }
}
