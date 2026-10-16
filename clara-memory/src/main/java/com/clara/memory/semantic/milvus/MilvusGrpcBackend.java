package com.clara.memory.semantic.milvus;

import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Milvus SDK（{@code MilvusServiceClient}）的 {@link MilvusBackend} 实现，封装连接与集合操作。
 *
 * <p>集合字段固定为：主键 {@code id}（VarChar）、{@code source_type}、{@code source_id}、
 * {@code conversation_id}、{@code content}、{@code content_hash}、向量 {@code vector}（FloatVector）、
 * {@code created_at} / {@code expires_at}（Int64）、{@code metadata}（JSON）。
 *
 * <p>运行期依赖外部 Milvus 实例；本类按 SDK 标准 API 编写，编译通过即可，真实检索需连接实例验证。
 */
public final class MilvusGrpcBackend implements MilvusBackend {

    private static final String FIELD_ID = "id";
    private static final String FIELD_SOURCE_TYPE = "source_type";
    private static final String FIELD_SOURCE_ID = "source_id";
    private static final String FIELD_CONVERSATION_ID = "conversation_id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_CONTENT_HASH = "content_hash";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_CREATED_AT = "created_at";
    private static final String FIELD_EXPIRES_AT = "expires_at";
    private static final String FIELD_METADATA = "metadata";

    /**
     * 检索时回传的标量 / 向量字段。
     */
    private static final List<String> OUT_FIELDS = Collections.unmodifiableList(Arrays.asList(
            FIELD_ID, FIELD_SOURCE_TYPE, FIELD_SOURCE_ID, FIELD_CONVERSATION_ID,
            FIELD_CONTENT, FIELD_CONTENT_HASH, FIELD_VECTOR,
            FIELD_CREATED_AT, FIELD_EXPIRES_AT, FIELD_METADATA));

    private final MilvusServiceClient client;
    private volatile MilvusCollectionSpec activeSpec;

    /**
     * 创建后端，包裹已有客户端。
     *
     * @param client Milvus 客户端，不可为 null
     */
    public MilvusGrpcBackend(MilvusServiceClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        this.client = client;
    }

    @Override
    public MilvusCollectionSpec ensureCollection(MilvusCollectionSpec expected) {
        if (expected == null) {
            throw new IllegalArgumentException("expected must not be null");
        }
        String collection = expected.getCollection();
        R<Boolean> has = client.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collection).build());
        checkStatus(has, "hasCollection");
        if (Boolean.TRUE.equals(has.getData())) {
            int actualDimension = describeDimension(collection);
            if (actualDimension != expected.getDimension()) {
                throw new MilvusBackendException("existing milvus collection dimension mismatch: expected "
                        + expected.getDimension() + " but found " + actualDimension, false);
            }
            this.activeSpec = expected;
            return expected;
        }
        createCollection(expected);
        createIndex(collection, expected);
        loadCollection(collection);
        this.activeSpec = expected;
        return expected;
    }

    @Override
    public void upsert(String collection, List<MemoryEntry> entries) {
        if (collection == null) {
            throw new IllegalArgumentException("collection must not be null");
        }
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        if (entries.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<String>();
        List<String> sourceTypes = new ArrayList<String>();
        List<String> sourceIds = new ArrayList<String>();
        List<String> conversationIds = new ArrayList<String>();
        List<String> contents = new ArrayList<String>();
        List<String> contentHashes = new ArrayList<String>();
        List<List<Float>> vectors = new ArrayList<List<Float>>();
        List<Long> createdAts = new ArrayList<Long>();
        List<Long> expiresAts = new ArrayList<Long>();
        List<JsonObject> metadatas = new ArrayList<JsonObject>();
        for (MemoryEntry entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException("entries must not contain null");
            }
            ids.add(entry.getId());
            sourceTypes.add(entry.getSourceType().name());
            sourceIds.add(entry.getSourceId());
            conversationIds.add(entry.getConversationId() == null ? "" : entry.getConversationId());
            contents.add(entry.getContent());
            contentHashes.add(entry.getContentHash());
            vectors.add(toFloatList(entry.getVector()));
            createdAts.add(Long.valueOf(entry.getCreatedAtEpochMillis()));
            expiresAts.add(Long.valueOf(entry.getExpiresAtEpochMillis()));
            metadatas.add(toJson(entry.getMetadata()));
        }
        List<InsertParam.Field> fields = new ArrayList<InsertParam.Field>();
        fields.add(field(FIELD_ID, ids));
        fields.add(field(FIELD_SOURCE_TYPE, sourceTypes));
        fields.add(field(FIELD_SOURCE_ID, sourceIds));
        fields.add(field(FIELD_CONVERSATION_ID, conversationIds));
        fields.add(field(FIELD_CONTENT, contents));
        fields.add(field(FIELD_CONTENT_HASH, contentHashes));
        fields.add(field(FIELD_VECTOR, vectors));
        fields.add(field(FIELD_CREATED_AT, createdAts));
        fields.add(field(FIELD_EXPIRES_AT, expiresAts));
        fields.add(field(FIELD_METADATA, metadatas));
        R<MutationResult> result = client.insert(InsertParam.newBuilder()
                .withCollectionName(collection)
                .withFields(fields)
                .build());
        checkStatus(result, "insert");
    }

    @Override
    public List<MemoryMatch> search(String collection, MilvusBackendQuery backendQuery) {
        if (collection == null || backendQuery == null) {
            throw new IllegalArgumentException("collection and query must not be null");
        }
        SemanticMemoryQuery query = backendQuery.getQuery();
        String expr = buildExpr(query);
        SearchParam.Builder builder = SearchParam.newBuilder()
                .withCollectionName(collection)
                .withVectorFieldName(FIELD_VECTOR)
                .withMetricType(metricType(currentMetric()))
                .withTopK(Integer.valueOf(backendQuery.getCandidateLimit()))
                .withOutFields(OUT_FIELDS)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withFloatVectors(Collections.singletonList(toFloatList(query.getVector())));
        if (!expr.isEmpty()) {
            builder.withExpr(expr);
        }
        R<SearchResults> result = client.search(builder.build());
        checkStatus(result, "search");
        SearchResultsWrapper wrapper = new SearchResultsWrapper(result.getData().getResults());
        List<QueryResultsWrapper.RowRecord> records = wrapper.getRowRecords(0);
        List<MemoryMatch> matches = new ArrayList<MemoryMatch>();
        for (QueryResultsWrapper.RowRecord record : records) {
            MemoryEntry entry = toEntry(record);
            double score = ((Number) record.get("score")).doubleValue();
            matches.add(new MemoryMatch(entry, score));
        }
        return matches;
    }

    @Override
    public void deleteById(String collection, String entryId) {
        if (collection == null || entryId == null) {
            throw new IllegalArgumentException("collection and entryId must not be null");
        }
        String expr = FIELD_ID + " in [\"" + escape(entryId) + "\"]";
        R<MutationResult> result = client.delete(DeleteParam.newBuilder()
                .withCollectionName(collection)
                .withExpr(expr)
                .build());
        checkStatus(result, "deleteById");
    }

    @Override
    public void deleteByConversation(String collection, String conversationId) {
        if (collection == null || conversationId == null) {
            throw new IllegalArgumentException("collection and conversationId must not be null");
        }
        String expr = FIELD_SOURCE_TYPE + " == \"" + MemorySourceTypeEnum.CONVERSATION.name() + "\""
                + " and " + FIELD_CONVERSATION_ID + " == \"" + escape(conversationId) + "\"";
        R<MutationResult> result = client.delete(DeleteParam.newBuilder()
                .withCollectionName(collection)
                .withExpr(expr)
                .build());
        checkStatus(result, "deleteByConversation");
    }

    @Override
    public void dropCollection(String collection) {
        if (collection == null) {
            throw new IllegalArgumentException("collection must not be null");
        }
        R<RpcStatus> result = client.dropCollection(DropCollectionParam.newBuilder()
                .withCollectionName(collection)
                .build());
        checkStatus(result, "dropCollection");
    }

    @Override
    public void close() {
        try {
            client.close(3L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ 集合创建

    private void createCollection(MilvusCollectionSpec spec) {
        List<FieldType> fields = new ArrayList<FieldType>();
        fields.add(FieldType.newBuilder().withName(FIELD_ID).withDataType(DataType.VarChar)
                .withMaxLength(Integer.valueOf(128)).withPrimaryKey(true).build());
        fields.add(FieldType.newBuilder().withName(FIELD_SOURCE_TYPE).withDataType(DataType.VarChar)
                .withMaxLength(Integer.valueOf(64)).build());
        fields.add(FieldType.newBuilder().withName(FIELD_SOURCE_ID).withDataType(DataType.VarChar)
                .withMaxLength(Integer.valueOf(256)).build());
        fields.add(FieldType.newBuilder().withName(FIELD_CONVERSATION_ID).withDataType(DataType.VarChar)
                .withMaxLength(Integer.valueOf(256)).build());
        fields.add(FieldType.newBuilder().withName(FIELD_CONTENT).withDataType(DataType.VarChar)
                .withMaxLength(Integer.valueOf(65535)).build());
        fields.add(FieldType.newBuilder().withName(FIELD_CONTENT_HASH).withDataType(DataType.VarChar)
                .withMaxLength(Integer.valueOf(64)).build());
        fields.add(FieldType.newBuilder().withName(FIELD_VECTOR).withDataType(DataType.FloatVector)
                .withDimension(Integer.valueOf(spec.getDimension())).build());
        fields.add(FieldType.newBuilder().withName(FIELD_CREATED_AT).withDataType(DataType.Int64).build());
        fields.add(FieldType.newBuilder().withName(FIELD_EXPIRES_AT).withDataType(DataType.Int64).build());
        fields.add(FieldType.newBuilder().withName(FIELD_METADATA).withDataType(DataType.JSON).build());
        R<RpcStatus> result = client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(spec.getCollection())
                .withDescription("clara semantic memory")
                .withFieldTypes(fields)
                .build());
        checkStatus(result, "createCollection");
    }

    private void createIndex(String collection, MilvusCollectionSpec spec) {
        R<RpcStatus> result = client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collection)
                .withFieldName(FIELD_VECTOR)
                .withIndexType(indexType(spec.getIndexType()))
                .withMetricType(metricType(spec.getMetricType()))
                .withExtraParam(extraParam(spec.getIndexType()))
                .build());
        checkStatus(result, "createIndex");
    }

    private void loadCollection(String collection) {
        R<RpcStatus> result = client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collection)
                .build());
        checkStatus(result, "loadCollection");
    }

    private int describeDimension(String collection) {
        R<DescribeCollectionResponse> result = client.describeCollection(DescribeCollectionParam.newBuilder()
                .withCollectionName(collection)
                .build());
        checkStatus(result, "describeCollection");
        DescribeCollectionResponse data = result.getData();
        if (data == null || data.getSchema() == null) {
            throw new MilvusBackendException("milvus describeCollection returned no schema", false);
        }
        for (FieldSchema field : data.getSchema().getFieldsList()) {
            if (field.getDataType() == DataType.FloatVector) {
                for (KeyValuePair pair : field.getTypeParamsList()) {
                    if ("dim".equals(pair.getKey())) {
                        return Integer.parseInt(pair.getValue());
                    }
                }
            }
        }
        throw new MilvusBackendException("milvus collection has no float vector field", false);
    }

    // ------------------------------------------------------------------ 数据转换

    private MemoryEntry toEntry(QueryResultsWrapper.RowRecord record) {
        String conversationId = asString(record.get(FIELD_CONVERSATION_ID));
        if (conversationId != null && conversationId.isEmpty()) {
            conversationId = null;
        }
        return new MemoryEntry(
                asString(record.get(FIELD_ID)),
                MemorySourceTypeEnum.valueOf(asString(record.get(FIELD_SOURCE_TYPE))),
                asString(record.get(FIELD_SOURCE_ID)),
                conversationId,
                asString(record.get(FIELD_CONTENT)),
                asString(record.get(FIELD_CONTENT_HASH)),
                toFloatArray(record.get(FIELD_VECTOR)),
                ((Number) record.get(FIELD_CREATED_AT)).longValue(),
                ((Number) record.get(FIELD_EXPIRES_AT)).longValue(),
                toMetadata(record.get(FIELD_METADATA)));
    }

    private static InsertParam.Field field(String name, List<?> values) {
        return InsertParam.Field.builder().name(name).values(values).build();
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<Float>(vector.length);
        for (float value : vector) {
            values.add(Float.valueOf(value));
        }
        return values;
    }

    private static float[] toFloatArray(Object value) {
        if (!(value instanceof List)) {
            return new float[0];
        }
        List<?> list = (List<?>) value;
        float[] result = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = ((Number) list.get(i)).floatValue();
        }
        return result;
    }

    private static JsonObject toJson(Map<String, String> metadata) {
        JsonObject object = new JsonObject();
        if (metadata != null) {
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                object.addProperty(entry.getKey(), entry.getValue());
            }
        }
        return object;
    }

    private static Map<String, String> toMetadata(Object value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (value instanceof JsonObject) {
            JsonObject object = (JsonObject) value;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                    result.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        return result;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ------------------------------------------------------------------ 表达式与映射

    private static String buildExpr(SemanticMemoryQuery query) {
        StringBuilder expr = new StringBuilder();
        if (query.getSourceType() != null) {
            expr.append(FIELD_SOURCE_TYPE).append(" == \"").append(query.getSourceType().name()).append("\"");
        }
        if (query.getConversationId() != null) {
            if (expr.length() > 0) {
                expr.append(" and ");
            }
            expr.append(FIELD_CONVERSATION_ID).append(" == \"").append(escape(query.getConversationId())).append("\"");
        }
        return expr.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String currentMetric() {
        MilvusCollectionSpec spec = activeSpec;
        return spec == null ? MilvusCollectionSpec.COSINE : spec.getMetricType();
    }

    private static IndexType indexType(String value) {
        if ("HNSW".equalsIgnoreCase(value)) {
            return IndexType.HNSW;
        }
        if ("FLAT".equalsIgnoreCase(value)) {
            return IndexType.FLAT;
        }
        return IndexType.IVF_FLAT;
    }

    private static MetricType metricType(String value) {
        if ("L2".equalsIgnoreCase(value)) {
            return MetricType.L2;
        }
        if ("IP".equalsIgnoreCase(value)) {
            return MetricType.IP;
        }
        return MetricType.COSINE;
    }

    private static String extraParam(String indexType) {
        if ("HNSW".equalsIgnoreCase(indexType)) {
            return "{\"M\":16,\"efConstruction\":256}";
        }
        if ("FLAT".equalsIgnoreCase(indexType)) {
            return "{}";
        }
        return "{\"nlist\":128}";
    }

    private static void checkStatus(R<?> response, String operation) {
        if (response == null) {
            throw new MilvusBackendException("milvus " + operation + " returned null", false);
        }
        if (response.getStatus() == null || response.getStatus().intValue() != R.Status.Success.getCode()) {
            String message = response.getException() == null
                    ? String.valueOf(response.getStatus())
                    : response.getException().getMessage();
            throw new MilvusBackendException("milvus " + operation + " failed: " + message, true);
        }
    }
}
