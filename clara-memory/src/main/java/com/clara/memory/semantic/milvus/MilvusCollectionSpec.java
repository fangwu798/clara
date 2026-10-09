package com.clara.memory.semantic.milvus;

/**
 * Milvus 集合规格：描述集合名称、向量维度、索引类型与距离度量。
 *
 * <p>嵌入模型需全程一致（入库与检索同模型、同维度），因此集合标识（名称 + 维度 + 度量）用于
 * {@code ensureCollection} 时校验既有集合是否匹配；索引类型只影响建索引参数，不参与身份比较。
 */
public final class MilvusCollectionSpec {

    /**
     * 默认距离度量：余弦相似度。
     */
    public static final String COSINE = "COSINE";

    /**
     * 默认索引类型：IVF_FLAT。
     */
    public static final String IVF_FLAT = "IVF_FLAT";

    private final String collection;
    private final int dimension;
    private final String metricType;
    private final String indexType;

    /**
     * 创建集合规格。
     *
     * @param collection 集合名称，不可为空白
     * @param dimension  向量维度，必须为正
     * @param metricType 距离度量，不可为空白
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public MilvusCollectionSpec(String collection, int dimension, String metricType) {
        this(collection, dimension, metricType, IVF_FLAT);
    }

    /**
     * 创建集合规格。
     *
     * @param collection 集合名称，不可为空白
     * @param dimension  向量维度，必须为正
     * @param metricType 距离度量，不可为空白
     * @param indexType  索引类型（如 {@code IVF_FLAT} / {@code HNSW}），不可为空白
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public MilvusCollectionSpec(String collection, int dimension, String metricType, String indexType) {
        if (blank(collection) || dimension <= 0 || blank(metricType) || blank(indexType)) {
            throw new IllegalArgumentException("milvus collection specification is invalid");
        }
        this.collection = collection;
        this.dimension = dimension;
        this.metricType = metricType;
        this.indexType = indexType;
    }

    /**
     * 创建使用余弦相似度与默认索引的集合规格。
     *
     * @param collection 集合名称，不可为空白
     * @param dimension  向量维度，必须为正
     */
    public MilvusCollectionSpec(String collection, int dimension) {
        this(collection, dimension, COSINE);
    }

    public String getCollection() {
        return collection;
    }

    public int getDimension() {
        return dimension;
    }

    public String getMetricType() {
        return metricType;
    }

    public String getIndexType() {
        return indexType;
    }

    /**
     * 判断两个集合规格是否同源（可复用同一既有集合）。
     */
    public boolean sameIdentity(MilvusCollectionSpec other) {
        return other != null
                && collection.equals(other.collection)
                && dimension == other.dimension
                && metricType.equals(other.metricType);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
