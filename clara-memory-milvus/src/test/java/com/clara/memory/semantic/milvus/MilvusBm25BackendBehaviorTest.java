package com.clara.memory.semantic.milvus;

import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link MilvusBm25Backend} 的集合结构校验、写入与检索映射测试。
 *
 * <p>为什么值得测：BM25 集合与向量集合结构不兼容，模式切换时必须在启动期给出明确的
 * 「需要重建集合」而不是等到第一次召回才报错；这些判断都落在本类的私有分支里，
 * 用假客户端走一遍真实编排才能覆盖到。
 */
public class MilvusBm25BackendBehaviorTest {

    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void constructorRejectsNullClient() {
        try {
            new MilvusBm25Backend(null);
            fail("null client should be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("client must not be null", expected.getMessage());
        }
    }

    @Test
    public void ensureCollectionRejectsNullAndVectorSpec() {
        MilvusBm25Backend backend = new MilvusBm25Backend(FakeV2Client.create());
        try {
            backend.ensureCollection(null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bm25 collection specification"));
        }
        try {
            backend.ensureCollection(new MilvusCollectionSpec("conversations", 4, "COSINE"));
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bm25 collection specification"));
        }
    }

    @Test
    public void ensureCollectionCreatesBm25SchemaWhenMissing() {
        FakeV2Client client = FakeV2Client.create();
        MilvusBm25Backend backend = new MilvusBm25Backend(client);
        MilvusCollectionSpec spec = new MilvusCollectionSpec("conversations", "chinese");

        assertSame(spec, backend.ensureCollection(spec));

        assertTrue(client.created);
        assertTrue(client.loaded);
        assertEquals("BM25 集合应有 10 个字段", 10, client.createdFieldCount);
        assertEquals("分词器必须随集合一起写入", "chinese", client.analyzer);
        assertTrue("必须声明 BM25 函数", client.functionCount == 1);
    }

    @Test
    public void ensureCollectionAcceptsExistingBm25Schema() {
        FakeV2Client client = FakeV2Client.create();
        client.exists = true;
        client.fields = Arrays.asList(
                field("content", DataType.VarChar).analyzerParams(
                        Collections.<String, Object>singletonMap("type", "chinese")).build(),
                field("sparse", DataType.SparseFloatVector).build());
        MilvusBm25Backend backend = new MilvusBm25Backend(client);
        MilvusCollectionSpec spec = new MilvusCollectionSpec("conversations", "chinese");

        assertSame(spec, backend.ensureCollection(spec));
        assertTrue("已存在的 BM25 集合不应重建", !client.created);
    }

    @Test
    public void existingDenseVectorCollectionIsRejected() {
        FakeV2Client client = FakeV2Client.create();
        client.exists = true;
        client.fields = Arrays.asList(
                field("content", DataType.VarChar).build(),
                field("vector", DataType.FloatVector).build());
        MilvusBm25Backend backend = new MilvusBm25Backend(client);
        try {
            backend.ensureCollection(new MilvusCollectionSpec("conversations", "chinese"));
            fail();
        } catch (MilvusBackendException expected) {
            assertTrue(expected.getMessage().contains("dense-vector collection"));
            assertTrue("模式冲突必须人工重建，不能自动重试", !expected.isRetryable());
        }
    }

    @Test
    public void existingCollectionWithoutSparseFieldIsRejected() {
        FakeV2Client client = FakeV2Client.create();
        client.exists = true;
        client.fields = Collections.singletonList(field("content", DataType.VarChar).build());
        MilvusBm25Backend backend = new MilvusBm25Backend(client);
        try {
            backend.ensureCollection(new MilvusCollectionSpec("conversations", "chinese"));
            fail();
        } catch (MilvusBackendException expected) {
            assertTrue(expected.getMessage().contains("no sparse vector field"));
        }
    }

    @Test
    public void analyzerMismatchIsRejected() {
        FakeV2Client client = FakeV2Client.create();
        client.exists = true;
        client.fields = Arrays.asList(
                field("content", DataType.VarChar).analyzerParams(
                        Collections.<String, Object>singletonMap("type", "english")).build(),
                field("sparse", DataType.SparseFloatVector).build());
        MilvusBm25Backend backend = new MilvusBm25Backend(client);
        try {
            backend.ensureCollection(new MilvusCollectionSpec("conversations", "chinese"));
            fail();
        } catch (MilvusBackendException expected) {
            assertTrue(expected.getMessage().contains("uses analyzer english"));
        }
    }

    @Test
    public void missingAnalyzerParamsOnOldServerIsTolerated() {
        FakeV2Client client = FakeV2Client.create();
        client.exists = true;
        client.fields = Arrays.asList(
                field("content", DataType.VarChar).build(),
                field("sparse", DataType.SparseFloatVector).build());
        MilvusBm25Backend backend = new MilvusBm25Backend(client);
        MilvusCollectionSpec spec = new MilvusCollectionSpec("conversations", "chinese");
        assertSame("旧服务端不返回分词器参数时应信任既有集合", spec, backend.ensureCollection(spec));
    }

    @Test
    public void upsertValidatesArguments() {
        MilvusBm25Backend backend = new MilvusBm25Backend(FakeV2Client.create());
        try {
            backend.upsert(null, Collections.<MemoryEntry>emptyList());
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("collection and entries must not be null", expected.getMessage());
        }
        try {
            backend.upsert("conversations", null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("collection and entries must not be null", expected.getMessage());
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
        FakeV2Client client = FakeV2Client.create();
        new MilvusBm25Backend(client).upsert("conversations", Collections.<MemoryEntry>emptyList());
        assertEquals(0, client.insertRows);
    }

    @Test
    public void upsertWritesOneRowPerEntry() {
        FakeV2Client client = FakeV2Client.create();
        MilvusBm25Backend backend = new MilvusBm25Backend(client);
        MemoryEntry entry = MemoryEntry.builder("id-1", MemorySourceTypeEnum.CONVERSATION, "conv-1",
                "hello", HASH, new float[] {1f})
                .createdAtEpochMillis(1L)
                .build();

        backend.upsert("conversations", Collections.singletonList(entry));

        assertEquals(1, client.insertRows);
        assertEquals("conversations", client.lastUpsertCollection);
    }

    @Test
    public void searchMapsHitsAndSkipsNullEntities() {
        FakeV2Client client = FakeV2Client.create();
        MilvusBm25Backend backend = new MilvusBm25Backend(client);
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", "id-1");
        entity.put("source_type", "CONVERSATION");
        entity.put("source_id", "conv-1");
        entity.put("conversation_id", "");
        entity.put("content", "hello");
        entity.put("content_hash", HASH);
        entity.put("created_at", 10L);
        entity.put("expires_at", 20L);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("k", "v");
        entity.put("metadata", metadata);
        client.results = Arrays.asList(
                result(entity, 1d),
                result(null, 2d),
                result(entity, null));

        SemanticMemoryQuery query = SemanticMemoryQuery.builder(new float[] {1f}, 3, 0d, 0L)
                .queryText("hello")
                .sourceType(MemorySourceTypeEnum.CONVERSATION)
                .conversationId("conv-1")
                .build();
        List<MemoryMatch> matches = backend.search("conversations", new MilvusBackendQuery(query, 3));

        assertEquals(2, matches.size());
        assertEquals("id-1", matches.get(0).getEntry().getId());
        assertNull("空会话标识要归一为 null", matches.get(0).getEntry().getConversationId());
        assertEquals("v", matches.get(0).getEntry().getMetadata().get("k"));
        assertEquals(0.5d, matches.get(0).getScore(), 1e-9);
        assertEquals("缺失分数按 0 处理", 0d, matches.get(1).getScore(), 1e-9);
        assertTrue(client.lastSearchExpr.contains("source_type == \"CONVERSATION\""));
        assertTrue(client.lastSearchExpr.contains("conversation_id == \"conv-1\""));
    }

    @Test
    public void searchRequiresRawQueryText() {
        MilvusBm25Backend backend = new MilvusBm25Backend(FakeV2Client.create());
        SemanticMemoryQuery query = SemanticMemoryQuery.builder(new float[] {1f}, 3, 0d, 0L).build();
        try {
            backend.search("conversations", new MilvusBackendQuery(query, 3));
            fail("bm25 needs raw text");
        } catch (IllegalArgumentException expected) {
            assertEquals("bm25 search requires the raw query text", expected.getMessage());
        }
    }

    @Test
    public void searchValidatesArguments() {
        MilvusBm25Backend backend = new MilvusBm25Backend(FakeV2Client.create());
        try {
            backend.search(null, new MilvusBackendQuery(
                    SemanticMemoryQuery.builder(new float[] {1f}, 3, 0d, 0L).queryText("x").build(), 3));
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("collection and query must not be null", expected.getMessage());
        }
        try {
            backend.search("conversations", null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("collection and query must not be null", expected.getMessage());
        }
    }

    @Test
    public void deletesUseEscapedExpressions() {
        FakeV2Client client = FakeV2Client.create();
        MilvusBm25Backend backend = new MilvusBm25Backend(client);

        backend.deleteById("conversations", "a\"b");
        assertTrue(client.lastDeleteExpr.contains("id in"));
        assertTrue("引号需要转义", client.lastDeleteExpr.contains("\\\""));

        backend.deleteByConversation("conversations", "conv-1");
        assertTrue(client.lastDeleteExpr.contains("source_type == \"CONVERSATION\""));
        assertTrue(client.lastDeleteExpr.contains("conversation_id == \"conv-1\""));
    }

    @Test
    public void deleteAndDropValidateArguments() {
        MilvusBm25Backend backend = new MilvusBm25Backend(FakeV2Client.create());
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
        try {
            backend.dropCollection(null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertEquals("collection must not be null", expected.getMessage());
        }
    }

    @Test
    public void dropAndCloseDelegateToClient() {
        FakeV2Client client = FakeV2Client.create();
        MilvusBm25Backend backend = new MilvusBm25Backend(client);
        backend.dropCollection("conversations");
        backend.close();
        assertEquals("conversations", client.dropped);
        assertTrue(client.closed);
    }

    private static CreateCollectionReq.FieldSchema.FieldSchemaBuilder field(String name, DataType type) {
        return CreateCollectionReq.FieldSchema.builder().name(name).dataType(type);
    }

    private static SearchResp.SearchResult result(Map<String, Object> entity, Double score) {
        SearchResp.SearchResult.SearchResultBuilder builder = SearchResp.SearchResult.builder().entity(entity);
        if (score != null) {
            builder.score(score.floatValue());
        }
        return builder.build();
    }

    /**
     * 只替换网络调用的假 v2 客户端。
     */
    private static final class FakeV2Client extends MilvusClientV2 {
        private boolean exists;
        private boolean created;
        private boolean loaded;
        private boolean closed;
        private int createdFieldCount;
        private int functionCount;
        private String analyzer;
        private List<CreateCollectionReq.FieldSchema> fields = new ArrayList<>();
        private int insertRows;
        private String lastUpsertCollection;
        private List<SearchResp.SearchResult> results = new ArrayList<>();
        private String lastSearchExpr;
        private String lastDeleteExpr;
        private String dropped;

        private FakeV2Client() {
            super(ConnectConfig.builder().uri("http://127.0.0.1:19530").build());
        }

        /**
         * 绕开构造函数分配实例，避免 SDK 建立 gRPC 连接。
         */
        private static FakeV2Client create() {
            try {
                Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
                Field field = unsafeType.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                Object unsafe = field.get(null);
                Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
                return (FakeV2Client) allocate.invoke(unsafe, FakeV2Client.class);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("cannot allocate fake client", failure);
            }
        }

        @Override
        public Boolean hasCollection(HasCollectionReq request) {
            return Boolean.valueOf(exists);
        }

        @Override
        public DescribeCollectionResp describeCollection(DescribeCollectionReq request) {
            return DescribeCollectionResp.builder()
                    .collectionSchema(CreateCollectionReq.CollectionSchema.builder()
                            .fieldSchemaList(fields)
                            .build())
                    .build();
        }

        @Override
        public void createCollection(CreateCollectionReq request) {
            created = true;
            createdFieldCount = request.getCollectionSchema().getFieldSchemaList().size();
            functionCount = request.getCollectionSchema().getFunctionList().size();
            for (CreateCollectionReq.FieldSchema schema : request.getCollectionSchema().getFieldSchemaList()) {
                if (schema.getAnalyzerParams() != null) {
                    analyzer = String.valueOf(schema.getAnalyzerParams().get("type"));
                }
            }
        }

        @Override
        public void loadCollection(LoadCollectionReq request) {
            loaded = true;
        }

        @Override
        public InsertResp insert(InsertReq request) {
            insertRows = request.getData().size();
            lastUpsertCollection = request.getCollectionName();
            return InsertResp.builder().build();
        }

        @Override
        public SearchResp search(SearchReq request) {
            lastSearchExpr = request.getFilter();
            return SearchResp.builder()
                    .searchResults(Collections.singletonList(results))
                    .build();
        }

        @Override
        public DeleteResp delete(DeleteReq request) {
            lastDeleteExpr = request.getFilter();
            return DeleteResp.builder().build();
        }

        @Override
        public void dropCollection(DropCollectionReq request) {
            dropped = request.getCollectionName();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}