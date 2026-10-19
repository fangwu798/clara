package com.clara.memory.semantic.milvus;

import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.SearchResp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 基于 Milvus 内置 BM25 全文检索的 {@link MilvusBackend} 实现（不需要任何嵌入服务）。
 *
 * <p>是什么、在哪一层：{@code clara-memory} 的 Milvus 后端另一种实现（同包的 {@link MilvusGrpcBackend}
 * 负责向量相似度检索）。集合结构上把嵌入向量换成一个稀疏向量字段，由 Milvus 的 BM25
 * 函数从正文（{@code content}）自动生成；检索时直接把用户原文丢给 Milvus，服务端用同一个分词器
 * 切词后按 BM25 打分。
 *
 * <p>为什么需要：线上嵌入服务不可用时，占位哈希嵌入（{@code HashingEmbeddingClient}）不含语义，
 * 同一个问题与历史记录的余弦相似度只有 0.1 上下，永远过不了召回阈值；BM25 在 Milvus
 * 内部完成分词与打分，不依赖外部服务、也不需要下载模型，中文用 jieba（分词器 {@code chinese}）切词后召回质量足以承担
 * 「找回之前聊过的内容」。
 *
 * <p>分数口径：BM25 原始分数无上界（实测相关问题 4~8 分、无关问题 1 分下），而上层召回编排与
 * 阈值（{@code ContextControlConfig.similarityThreshold}，默认 0.70）按相似度 0~1 设定，因此这里用
 * {@code score/(score+1)} 单调压缩到 (0,1)：阈值 0.70 约等于原始 BM25 ≥ 2.33。
 *
 * <p>读取路径：BM25 集合里没有密集向量，而 {@link MemoryEntry} 要求非零向量，因此回传单位占位向量；
 * 召回协调器与技能匹配只用正文与来源标识，不使用该字段。
 *
 * <p>失败语义：SDK 异常统一包成 {@link MilvusBackendException}（可重试）；参数错误直接抛
 * {@link IllegalArgumentException}。
 */
public final class MilvusBm25Backend implements MilvusBackend {

    private static final String EQ_QUOTE = " == \"";
    private static final String COLLECTION_PREFIX = "milvus collection ";

    private static final String FIELD_ID = "id";
    private static final String FIELD_SOURCE_TYPE = "source_type";
    private static final String FIELD_SOURCE_ID = "source_id";
    private static final String FIELD_CONVERSATION_ID = "conversation_id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_CONTENT_HASH = "content_hash";
    private static final String FIELD_CREATED_AT = "created_at";
    private static final String FIELD_EXPIRES_AT = "expires_at";
    private static final String FIELD_METADATA = "metadata";
    private static final String FIELD_SPARSE = "sparse";

    /**
     * 向量模式的密集向量字段名：存在说明集合是向量集合，与 BM25 模式不兼容。
     */
    private static final String FIELD_VECTOR = "vector";

    /**
     * BM25 函数名：从正文生成稀疏向量的服务端函数。
     */
    private static final String FUNCTION_NAME = "content_bm25";

    /**
     * 单批写入的最大条数：正文字段很大（工具输出可达几 KB），批太大会把一次请求撑成几十 MB。
     */
    private static final int MAX_INSERT_BATCH = 200;

    /**
     * 读取时的占位向量：BM25 集合不存向量，而 {@link MemoryEntry} 要求非零向量。
     */
    private static final float[] PLACEHOLDER_VECTOR = new float[] {1f};

    /**
     * 检索时回传的字段：与向量后端保持一致（去掉向量字段），使上层拿到的条目结构相同。
     */
    private static final List<String> OUT_FIELDS = Collections.unmodifiableList(Arrays.asList(
            FIELD_ID, FIELD_SOURCE_TYPE, FIELD_SOURCE_ID, FIELD_CONVERSATION_ID,
            FIELD_CONTENT, FIELD_CONTENT_HASH, FIELD_CREATED_AT, FIELD_EXPIRES_AT, FIELD_METADATA));

    private final MilvusClientV2 client;

    /**
     * 创建 BM25 后端，包裹已建立的 v2 客户端。
     *
     * @param client Milvus v2 客户端，不可为 null
     * @throws IllegalArgumentException client 为 null 时
     */
    public MilvusBm25Backend(MilvusClientV2 client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        this.client = client;
    }

    @Override
    public MilvusCollectionSpec ensureCollection(MilvusCollectionSpec expected) {
        if (expected == null || !expected.isBm25()) {
            throw new IllegalArgumentException("bm25 backend requires a bm25 collection specification");
        }
        String collection = expected.getCollection();
        Boolean exists = execute("hasCollection", () -> client.hasCollection(
                HasCollectionReq.builder().collectionName(collection).build()));
        if (Boolean.TRUE.equals(exists)) {
            return verifyExisting(collection, expected);
        }
        createCollection(expected);
        execute("loadCollection", () -> {
            client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
            return null;
        });
        return expected;
    }

    @Override
    public void upsert(String collection, List<MemoryEntry> entries) {
        if (collection == null || entries == null) {
            throw new IllegalArgumentException("collection and entries must not be null");
        }
        if (entries.isEmpty()) {
            return;
        }
        for (int from = 0; from < entries.size(); from += MAX_INSERT_BATCH) {
            List<MemoryEntry> slice = entries.subList(from, Math.min(entries.size(), from + MAX_INSERT_BATCH));
            List<JsonObject> rows = new ArrayList<>(slice.size());
            for (MemoryEntry entry : slice) {
                if (entry == null) {
                    throw new IllegalArgumentException("entries must not contain null");
                }
                rows.add(row(entry));
            }
            execute("insert", () -> {
                client.insert(InsertReq.builder().collectionName(collection).data(rows).build());
                return null;
            });
        }
    }

    @Override
    public List<MemoryMatch> search(String collection, MilvusBackendQuery backendQuery) {
        if (collection == null || backendQuery == null) {
            throw new IllegalArgumentException("collection and query must not be null");
        }
        SemanticMemoryQuery query = backendQuery.getQuery();
        String text = query.getQueryText();
        if (text == null) {
            throw new IllegalArgumentException("bm25 search requires the raw query text");
        }
        SearchReq.SearchReqBuilder builder = SearchReq.builder()
                .collectionName(collection)
                .annsField(FIELD_SPARSE)
                .metricType(IndexParam.MetricType.BM25)
                .limit(backendQuery.getCandidateLimit())
                .outputFields(OUT_FIELDS)
                .data(Collections.<BaseVector>singletonList(new EmbeddedText(text)));
        String expr = buildExpr(query);
        if (!expr.isEmpty()) {
            builder.filter(expr);
        }
        SearchResp response = execute("search", () -> client.search(builder.build()));
        List<MemoryMatch> matches = new ArrayList<>();
        for (List<SearchResp.SearchResult> group : response.getSearchResults()) {
            for (SearchResp.SearchResult hit : group) {
                if (hit == null || hit.getEntity() == null) {
                    continue;
                }
                double score = hit.getScore() == null ? 0d : hit.getScore().doubleValue();
                matches.add(new MemoryMatch(toEntry(hit.getEntity()), toSimilarity(score)));
            }
        }
        return matches;
    }

    @Override
    public void deleteById(String collection, String entryId) {
        if (collection == null || entryId == null) {
            throw new IllegalArgumentException("collection and entryId must not be null");
        }
        delete(collection, FIELD_ID + " in [\"" + escape(entryId) + "\"]");
    }

    @Override
    public void deleteByConversation(String collection, String conversationId) {
        if (collection == null || conversationId == null) {
            throw new IllegalArgumentException("collection and conversationId must not be null");
        }
        delete(collection, FIELD_SOURCE_TYPE + EQ_QUOTE + MemorySourceTypeEnum.CONVERSATION.name()
                + "\" and " + FIELD_CONVERSATION_ID + EQ_QUOTE + escape(conversationId) + "\"");
    }

    @Override
    public void dropCollection(String collection) {
        if (collection == null) {
            throw new IllegalArgumentException("collection must not be null");
        }
        execute("dropCollection", () -> {
            client.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
            return null;
        });
    }

    @Override
    public void close() {
        client.close();
    }

    /**
     * 把 BM25 原始分数压缩为 0~1 的相似度：{@code score/(score+1)}（单调、无上界风险）。
     *
     * @param bm25Score Milvus 返回的 BM25 分数
     * @return 压缩后的相似度，范围 (0,1)；分数非正时为 0
     */
    static double toSimilarity(double bm25Score) {
        if (bm25Score <= 0d) {
            return 0d;
        }
        return bm25Score / (bm25Score + 1d);
    }

    /**
     * 创建 BM25 集合：正文字段开分词器，加一个稀疏向量字段与一个 BM25 函数（正文 → 稀疏向量），
     * 并在稀疏向量上建全文索引。
     *
     * @param spec 集合规格，非 null
     */
    private void createCollection(MilvusCollectionSpec spec) {
        List<CreateCollectionReq.FieldSchema> fields = new ArrayList<>();
        fields.add(CreateCollectionReq.FieldSchema.builder().name(FIELD_ID).dataType(DataType.VarChar)
                .maxLength(Integer.valueOf(128)).isPrimaryKey(true).build());
        fields.add(CreateCollectionReq.FieldSchema.builder().name(FIELD_SOURCE_TYPE).dataType(DataType.VarChar)
                .maxLength(Integer.valueOf(64)).build());
        fields.add(CreateCollectionReq.FieldSchema.builder().name(FIELD_SOURCE_ID).dataType(DataType.VarChar)
                .maxLength(Integer.valueOf(256)).build());
        fields.add(CreateCollectionReq.FieldSchema.builder().name(FIELD_CONVERSATION_ID).dataType(DataType.VarChar)
                .maxLength(Integer.valueOf(256)).build());
        fields.add(CreateCollectionReq.FieldSchema.builder().name(FIELD_CONTENT).dataType(DataType.VarChar)
                .maxLength(Integer.valueOf(65535)).enableAnalyzer(true)
                .analyzerParams(Collections.<String, Object>singletonMap("type", spec.getAnalyzer())).build());
        fields.add(CreateCollectionReq.FieldSchema.builder().name(FIELD_CONTENT_HASH).dataType(DataType.VarChar)
                .maxLength(Integer.valueOf(64)).build());
        fields.add(CreateCollectionReq.FieldSchema.builder().name(FIELD_CREATED_AT).dataType(DataType.Int64).build());
        fields.add(CreateCollectionReq.FieldSchema.builder().name(FIELD_EXPIRES_AT).dataType(DataType.Int64).build());
        fields.add(CreateCollectionReq.FieldSchema.builder().name(FIELD_METADATA).dataType(DataType.JSON).build());
        fields.add(CreateCollectionReq.FieldSchema.builder().name(FIELD_SPARSE)
                .dataType(DataType.SparseFloatVector).build());
        CreateCollectionReq.Function function = CreateCollectionReq.Function.builder()
                .name(FUNCTION_NAME).functionType(FunctionType.BM25)
                .inputFieldNames(Collections.singletonList(FIELD_CONTENT))
                .outputFieldNames(Collections.singletonList(FIELD_SPARSE)).build();
        IndexParam index = IndexParam.builder().fieldName(FIELD_SPARSE)
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25).build();
        execute("createCollection", () -> {
            client.createCollection(CreateCollectionReq.builder().collectionName(spec.getCollection())
                    .collectionSchema(CreateCollectionReq.CollectionSchema.builder()
                            .fieldSchemaList(fields)
                            .functionList(Collections.singletonList(function)).build())
                    .indexParams(Collections.singletonList(index)).build());
            return null;
        });
    }

    /**
     * 校验既有集合是否与 BM25 模式匹配。
     *
     * <p>为什么要校验：同一个集合名在向量模式与 BM25 模式下的结构完全不同，混用只会在检索
     * 时报错或得到空结果；与其等到第一次召回才失败，不如在启动期就把「需要重建集合」说清楚。
     *
     * @param collection 集合名称，非 null
     * @param expected   期望的规格，非 null
     * @return 校验通过时返回期望规格
     * @throws MilvusBackendException 集合存在但不是 BM25 结构（或分词器不一致）时
     */
    private MilvusCollectionSpec verifyExisting(String collection, MilvusCollectionSpec expected) {
        DescribeCollectionResp described = execute("describeCollection", () -> client.describeCollection(
                DescribeCollectionReq.builder().collectionName(collection).build()));
        CreateCollectionReq.CollectionSchema schema = described.getCollectionSchema();
        boolean sparse = false;
        if (schema != null) {
            for (CreateCollectionReq.FieldSchema field : schema.getFieldSchemaList()) {
                if (FIELD_VECTOR.equals(field.getName())) {
                    throw new MilvusBackendException(COLLECTION_PREFIX + collection
                            + " is a dense-vector collection but milvus.mode=bm25; "
                            + "\u5207\u6362\u6a21\u5f0f\u9700\u91cd\u5efa\u96c6\u5408\uff08milvus.recreateOnSchemaMismatch: true\uff09", false);
                }
                if (FIELD_SPARSE.equals(field.getName()) && field.getDataType() == DataType.SparseFloatVector) {
                    sparse = true;
                }
                if (FIELD_CONTENT.equals(field.getName())) {
                    checkAnalyzer(collection, expected, field);
                }
            }
        }
        if (!sparse) {
            throw new MilvusBackendException(COLLECTION_PREFIX + collection
                    + " has no sparse vector field, it is not a bm25 full-text collection; "
                    + "\u9700\u91cd\u5efa\u96c6\u5408\uff08milvus.recreateOnSchemaMismatch: true\uff09", false);
        }
        return expected;
    }

    /**
     * 校验既有集合正文字段的分词器是否与配置一致。
     *
     * <p>分词器不一致会让已存的稀疏向量与新查询的词表对不上（只会零命中）；而旧版服务端不返回
     * 分词器参数，这种情况下只能信任既有集合，不报错。
     *
     * @param collection 集合名称，非 null
     * @param expected   期望的规格，非 null
     * @param field      正文字段描述，非 null
     */
    private static void checkAnalyzer(String collection, MilvusCollectionSpec expected,
            CreateCollectionReq.FieldSchema field) {
        Map<String, Object> params = field.getAnalyzerParams();
        if (params == null || params.get("type") == null) {
            return;
        }
        String actual = String.valueOf(params.get("type"));
        if (!expected.getAnalyzer().equals(actual)) {
            throw new MilvusBackendException(COLLECTION_PREFIX + collection + " uses analyzer " + actual
                    + " but milvus.analyzer=" + expected.getAnalyzer()
                    + "; \u5207\u6362\u5206\u8bcd\u5668\u9700\u91cd\u5efa\u96c6\u5408\uff08milvus.recreateOnSchemaMismatch: true\uff09", false);
        }
    }

    /**
     * 按过滤表达式删除集合中的条目。
     *
     * @param collection 集合名称，非 null
     * @param filter     过滤表达式，非 null
     */
    private void delete(String collection, String filter) {
        execute("delete", () -> {
            client.delete(DeleteReq.builder().collectionName(collection).filter(filter).build());
            return null;
        });
    }

    /**
     * 把一条记忆编码为 Milvus 的一行：稀疏向量由 BM25 函数从正文生成，写入时不能带上。
     *
     * @param entry 记忆条目，非 null
     * @return JSON 行，非 null
     */
    private static JsonObject row(MemoryEntry entry) {
        JsonObject row = new JsonObject();
        row.addProperty(FIELD_ID, entry.getId());
        row.addProperty(FIELD_SOURCE_TYPE, entry.getSourceType().name());
        row.addProperty(FIELD_SOURCE_ID, entry.getSourceId());
        row.addProperty(FIELD_CONVERSATION_ID, entry.getConversationId() == null ? "" : entry.getConversationId());
        row.addProperty(FIELD_CONTENT, entry.getContent());
        row.addProperty(FIELD_CONTENT_HASH, entry.getContentHash());
        row.addProperty(FIELD_CREATED_AT, Long.valueOf(entry.getCreatedAtEpochMillis()));
        row.addProperty(FIELD_EXPIRES_AT, Long.valueOf(entry.getExpiresAtEpochMillis()));
        JsonObject metadata = new JsonObject();
        if (entry.getMetadata() != null) {
            for (Map.Entry<String, String> value : entry.getMetadata().entrySet()) {
                metadata.addProperty(value.getKey(), value.getValue());
            }
        }
        row.add(FIELD_METADATA, metadata);
        return row;
    }

    /**
     * 把检索命中的一行转为记忆条目。
     *
     * @param entity 命中行的字段表，非 null
     * @return 记忆条目，非 null
     */
    private static MemoryEntry toEntry(Map<String, Object> entity) {
        String conversationId = asString(entity.get(FIELD_CONVERSATION_ID));
        if (conversationId != null && conversationId.isEmpty()) {
            conversationId = null;
        }
        return MemoryEntry.builder(
                asString(entity.get(FIELD_ID)),
                MemorySourceTypeEnum.valueOf(asString(entity.get(FIELD_SOURCE_TYPE))),
                asString(entity.get(FIELD_SOURCE_ID)),
                asString(entity.get(FIELD_CONTENT)),
                asString(entity.get(FIELD_CONTENT_HASH)),
                PLACEHOLDER_VECTOR)
                .conversationId(conversationId)
                .createdAtEpochMillis(asLong(entity.get(FIELD_CREATED_AT)))
                .expiresAtEpochMillis(asLong(entity.get(FIELD_EXPIRES_AT)))
                .metadata(toMetadata(entity.get(FIELD_METADATA)))
                .build();
    }

    /**
     * 构造条目级过滤表达式：与向量后端一致（来源类型、会话）。
     *
     * @param query 查询条件，非 null
     * @return 过滤表达式；无条件时为空串
     */
    private static String buildExpr(SemanticMemoryQuery query) {
        StringBuilder expr = new StringBuilder();
        if (query.getSourceType() != null) {
            expr.append(FIELD_SOURCE_TYPE).append(EQ_QUOTE).append(query.getSourceType().name()).append("\"");
        }
        if (query.getConversationId() != null) {
            if (expr.length() > 0) {
                expr.append(" and ");
            }
            expr.append(FIELD_CONVERSATION_ID).append(EQ_QUOTE).append(escape(query.getConversationId())).append("\"");
        }
        return expr.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value));
    }

    /**
     * 把元数据字段还原为字符串映射：服务端可能返回 JSON 对象或普通 Map，两种都接。
     *
     * @param value 元数据字段值，可为 null
     * @return 元数据映射，非 null
     */
    private static Map<String, String> toMetadata(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof JsonObject) {
            for (Map.Entry<String, JsonElement> entry : ((JsonObject) value).entrySet()) {
                if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                    result.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } else if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue() == null
                        ? "" : String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    /**
     * 执行一次 SDK 调用，把 SDK 异常统一包成 {@link MilvusBackendException}。
     *
     * @param operation 操作名（用于错误信息），非 null
     * @param action    实际调用，非 null
     * @param <T>       返回类型
     * @return 调用结果
     */
    private static <T> T execute(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException failure) {
            throw new MilvusBackendException("milvus " + operation + " failed: " + failure.getMessage(), true);
        }
    }
}
