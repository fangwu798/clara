package com.clara.memory.semantic.milvus;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link MilvusBackendFactory} 与 {@link MilvusBackendException} 的单元测试。
 *
 * <p>为什么只测「选型」而不连实例：工厂的职责只是把配置翻译成对应 SDK 客户端——
 * bm25 模式用 v2 客户端、向量模式用 v1 客户端，两者不可互换。选错不会在构造期暴露，
 * 要等第一次检索才失败，因此这里把映射关系钉死。
 */
public class MilvusBackendFactoryTest {

    @Test
    public void rejectsNullConfig() {
        try {
            MilvusBackendFactory.create(null);
            fail("配置为空应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
        try {
            MilvusBackendFactory.create(null, new MilvusCollectionSpec("c", 3));
            fail("配置为空应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
    }

    @Test
    public void createsVectorBackendForVectorSpec() {
        MilvusBackend backend = MilvusBackendFactory.create(
                new MilvusBackendConfig("127.0.0.1", 19530), new MilvusCollectionSpec("c", 3));
        try {
            assertTrue("向量模式应使用 gRPC 后端", backend instanceof MilvusGrpcBackend);
        } finally {
            backend.close();
        }
    }

    @Test
    public void createsVectorBackendWhenSpecMissing() {
        MilvusBackend backend = MilvusBackendFactory.create(new MilvusBackendConfig("127.0.0.1", 19530));
        try {
            assertTrue("缺省规格等同向量模式", backend instanceof MilvusGrpcBackend);
        } finally {
            backend.close();
        }
    }

    @Test
    public void createsBm25BackendWithCredentials() {
        MilvusBackend backend = MilvusBackendFactory.create(
                new MilvusBackendConfig("127.0.0.1", 19530, "root", null, MilvusBackendConfig.DEFAULT_DATABASE,
                        MilvusBackendConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                        MilvusBackendConfig.DEFAULT_IDLE_TIMEOUT_MILLIS),
                new MilvusCollectionSpec("c", "chinese"));
        try {
            assertTrue("bm25 模式应使用 v2 客户端后端", backend instanceof MilvusBm25Backend);
        } finally {
            backend.close();
        }
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
}