package com.clara.memory.semantic.milvus;

import com.clara.dto.memory.MemoryEntry;
import com.clara.enums.memory.MemorySourceTypeEnum;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.CollectionSchema;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.MutationResult;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.index.CreateIndexParam;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link MilvusGrpcBackend} 的集合生命周期与参数校验测试。
 *
 * <p>为什么用假客户端：建集合、建索引、加载、维度校验、错误码映射这些分支只有在真正调用 SDK
 * 时才会执行，而连真实 Milvus 会让这批纯逻辑测试变慢、变不稳定。这里只替换网络调用，
 * 保留后端的全部编排代码，因此测到的是真实分支而不是复刻实现。
 *
 * <p>为什么用 Unsafe 分配实例：{@link MilvusServiceClient} 的构造函数会立刻建立连接，
 * 指向任意端口都会阻塞重试；测试只需要一个方法被替换的壳，不应触发网络。
 */
public class MilvusGrpcBackendTest {

    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void constructorRejectsNullClient() {
        try {
            new MilvusGrpcBackend(null);
            fail("null client should be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("client must not be null", expected.getMessage());
        }
    }

    @Test
    public void ensureCollectionRejectsNullSpec() {
        MilvusGrpcBackend backend = new MilvusGrpcBackend(FakeGrpcClient.create());
        try {
            backend.ensureCollection(null);
            fail("null spec should be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("expected must not be null", expected.getMessage());
        }
    }

    @Test
    public void ensureCollectionCreatesIndexAndLoadsWhenMissing() {
        FakeGrpcClient client = FakeGrpcClient.create();
        MilvusGrpcBackend backend = new MilvusGrpcBackend(client);
        MilvusCollectionSpec spec = new MilvusCollectionSpec("conversations", 4, "COSINE", "HNSW");

        assertSame(spec, backend.ensureCollection(spec));

        assertTrue("missing collection should be created", client.created);
        assertTrue("index should be created", client.indexed);
        assertTrue("collection should be loaded", client.loaded);
        assertEquals(10, client.createdFieldCount);
        assertTrue("HNSW index params should be used", client.indexExtraParam.contains("efConstruction"));
    }

    @Test
    public void ensureCollectionAcceptsMatchingDimension() {
        FakeGrpcClient client = FakeGrpcClient.create();
        client.exists = true;
        client.dimension = 4;
        MilvusGrpcBackend backend = new MilvusGrpcBackend(client);
        MilvusCollectionSpec spec = new MilvusCollectionSpec("conversations", 4, "L2", "FLAT");

        assertSame(spec, backend.ensureCollection(spec));
        assertTrue("existing collection must not be recreated", !client.created);
        assertTrue("现有集合不应重复建索引", !client.indexed);
    }

    @Test
    public void ensureCollectionRejectsDimensionMismatch() {
        FakeGrpcClient client = FakeGrpcClient.create();
        client.exists = true;
        client.dimension = 8;
        MilvusGrpcBackend backend = new MilvusGrpcBackend(client);
        try {
            backend.ensureCollection(new MilvusCollectionSpec("conversations", 4, "COSINE"));
            fail("dimension mismatch should fail fast");
        } catch (MilvusBackendException expected) {
            assertTrue(expected.getMessage().contains("dimension mismatch"));
            assertTrue("结构不匹配不应标记为可重试", !expected.isRetryable());
        }
    }

    @Test
    public void ensureCollectionRejectsSchemaWithoutVectorField() {
        FakeGrpcClient client = FakeGrpcClient.create();
        client.exists = true;
        client.dimension = -1;
        MilvusGrpcBackend backend = new MilvusGrpcBackend(client);
        try {
            backend.ensureCollection(new MilvusCollectionSpec("conversations", 4, "COSINE"));
            fail("missing vector field should fail fast");
        } catch (MilvusBackendException expected) {
            assertTrue(expected.getMessage().contains("no float vector field"));
        }
    }

    @Test
    public void sdkFailureIsWrappedAsRetryable() {
        FakeGrpcClient client = FakeGrpcClient.create();
        client.fail = true;
        MilvusGrpcBackend backend = new MilvusGrpcBackend(client);
        try {
            backend.ensureCollection(new MilvusCollectionSpec("conversations", 4, "COSINE"));
            fail("sdk failure should surface");
        } catch (MilvusBackendException expected) {
            assertTrue(expected.getMessage().contains("hasCollection"));
            assertTrue("平台/网络失败应可重试", expected.isRetryable());
        }
    }

    @Test
    public void upsertValidatesArguments() {
        MilvusGrpcBackend backend = new MilvusGrpcBackend(FakeGrpcClient.create());
        try {
            backend.upsert(null, Collections.<MemoryEntry>emptyList());
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("collection must not be null", expected.getMessage());
        }
        try {
            backend.upsert("conversations", null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("entries must not be null", expected.getMessage());
        }
        try {
            backend.upsert("conversations", Collections.<MemoryEntry>singletonList(null));
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("entries must not contain null", expected.getMessage());
        }
    }

    @Test
    public void emptyUpsertSkipsSdkCall() {
        FakeGrpcClient client = FakeGrpcClient.create();
        new MilvusGrpcBackend(client).upsert("conversations", Collections.<MemoryEntry>emptyList());
        assertEquals("empty batch should not reach the sdk", 0, client.insertCount);
    }

    @Test
    public void upsertSendsAllFieldsAndMetadata() {
        FakeGrpcClient client = FakeGrpcClient.create();
        MilvusGrpcBackend backend = new MilvusGrpcBackend(client);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("k", "v");
        MemoryEntry entry = MemoryEntry.builder("id-1", MemorySourceTypeEnum.CONVERSATION, "conv-1",
                "hello", HASH, new float[] {0.5f, 0.5f})
                .conversationId("conv-1")
                .createdAtEpochMillis(10L)
                .expiresAtEpochMillis(20L)
                .metadata(metadata)
                .build();

        backend.upsert("conversations", Collections.singletonList(entry));

        assertEquals(1, client.insertCount);
        assertEquals("one column per schema field", 10, client.lastInsertFields);
        assertEquals("conversations", client.lastInsertCollection);
    }

    @Test
    public void deletesUseEscapedExpressions() {
        FakeGrpcClient client = FakeGrpcClient.create();
        MilvusGrpcBackend backend = new MilvusGrpcBackend(client);

        backend.deleteById("conversations", "a\"b");
        assertTrue(client.lastDeleteExpr.contains("id in"));
        assertTrue("引号需要转义，否则表达式会注入", client.lastDeleteExpr.contains("\\\""));

        backend.deleteByConversation("conversations", "conv-1");
        assertTrue(client.lastDeleteExpr.contains("source_type == \"CONVERSATION\""));
        assertTrue(client.lastDeleteExpr.contains("conversation_id == \"conv-1\""));
    }

    @Test
    public void deleteValidatesArguments() {
        MilvusGrpcBackend backend = new MilvusGrpcBackend(FakeGrpcClient.create());
        try {
            backend.deleteById(null, "id");
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("collection and entryId must not be null", expected.getMessage());
        }
        try {
            backend.deleteByConversation("conversations", null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("collection and conversationId must not be null", expected.getMessage());
        }
    }

    @Test
    public void dropCollectionValidatesAndDelegates() {
        FakeGrpcClient client = FakeGrpcClient.create();
        MilvusGrpcBackend backend = new MilvusGrpcBackend(client);
        try {
            backend.dropCollection(null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("collection must not be null", expected.getMessage());
        }
        backend.dropCollection("conversations");
        assertEquals("conversations", client.droppedCollection);
    }

    @Test
    public void closeRestoresInterruptFlag() {
        FakeGrpcClient client = FakeGrpcClient.create();
        client.interruptOnClose = true;
        new MilvusGrpcBackend(client).close();
        assertTrue("中断状态必须保留给上层", Thread.currentThread().isInterrupted());
        Thread.interrupted();
        assertTrue(client.closed);
    }

    /**
     * 只替换网络调用的假客户端；其余 SDK 方法不会被后端调用。
     */
    private static final class FakeGrpcClient extends MilvusServiceClient {
        private boolean exists;
        private boolean fail;
        private boolean interruptOnClose;
        private int dimension = 4;
        private boolean created;
        private boolean indexed;
        private boolean loaded;
        private boolean closed;
        private int createdFieldCount;
        private String indexExtraParam;
        private int insertCount;
        private int lastInsertFields;
        private String lastInsertCollection;
        private String lastDeleteExpr;
        private String droppedCollection;

        private FakeGrpcClient() {
            super(ConnectParam.newBuilder().withHost("127.0.0.1").withPort(1).build());
        }

        /**
         * 绕开构造函数分配实例：SDK 构造函数会建立 gRPC 连接，单测不需要也不应该触发。
         */
        private static FakeGrpcClient create() {
            try {
                Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
                Field field = unsafeType.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                Object unsafe = field.get(null);
                Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
                FakeGrpcClient client = (FakeGrpcClient) allocate.invoke(unsafe, FakeGrpcClient.class);
                client.dimension = 4;
                return client;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("cannot allocate fake client", failure);
            }
        }

        @Override
        public R<Boolean> hasCollection(HasCollectionParam param) {
            return fail ? R.failed(R.Status.UnexpectedError, "boom") : R.success(exists);
        }

        @Override
        public R<RpcStatus> createCollection(CreateCollectionParam param) {
            created = true;
            createdFieldCount = param.getFieldTypes().size();
            return R.success();
        }

        @Override
        public R<RpcStatus> createIndex(CreateIndexParam param) {
            indexed = true;
            indexExtraParam = String.valueOf(param.getExtraParam());
            return R.success();
        }

        @Override
        public R<RpcStatus> loadCollection(LoadCollectionParam param) {
            loaded = true;
            return R.success();
        }

        @Override
        public R<DescribeCollectionResponse> describeCollection(DescribeCollectionParam param) {
            if (dimension < 0) {
                return R.success(DescribeCollectionResponse.newBuilder()
                        .setSchema(CollectionSchema.newBuilder().build())
                        .build());
            }
            FieldSchema field = FieldSchema.newBuilder()
                    .setName("vector")
                    .setDataType(DataType.FloatVector)
                    .addTypeParams(KeyValuePair.newBuilder().setKey("dim")
                            .setValue(String.valueOf(dimension)).build())
                    .build();
            return R.success(DescribeCollectionResponse.newBuilder()
                    .setSchema(CollectionSchema.newBuilder().addFields(field).build())
                    .build());
        }

        @Override
        public R<MutationResult> insert(InsertParam param) {
            insertCount++;
            lastInsertFields = param.getFields().size();
            lastInsertCollection = param.getCollectionName();
            return R.success();
        }

        @Override
        public R<MutationResult> delete(DeleteParam param) {
            lastDeleteExpr = param.getExpr();
            return R.success();
        }

        @Override
        public R<RpcStatus> dropCollection(DropCollectionParam param) {
            droppedCollection = param.getCollectionName();
            return R.success();
        }

        @Override
        public void close(long timeoutSeconds) throws InterruptedException {
            closed = true;
            if (interruptOnClose) {
                throw new InterruptedException("interrupted");
            }
        }
    }
}