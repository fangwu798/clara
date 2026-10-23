package com.clara.memory.semantic.milvus;

import com.clara.utils.StringUtils;
import lombok.Getter;

/**
 * Milvus 集合规格：描述集合名称、向量维度、索引类型与距离度量。
 *
 * <p>嵌入模型需全程一致（入库与检索同模型、同维度），因此集合标识（名称 + 维度 + 度量）用于
 * {@code ensureCollection} 时校验既有集合是否匹配；索引类型只影响建索引参数，不参与身份比较。
 *
 * <p>检索模式（{@code mode}）决定集合结构：{@code vector} 模式存密集向量，身份包含维度；
 * {@code bm25} 模式存全文稀疏向量（由分词函数自动生成），没有维度概念，身份改由分词器决定。
 * 两种模式的集合互不兼容，切换模式必须重建集合。
 */
@Getter
public final class MilvusCollectionSpec {

    /**
     * 默认距离度量：余弦相似度。
     */
    public static final String COSINE = "COSINE";

    /**
     * 默认索引类型：IVF_FLAT。
     */
    public static final String IVF_FLAT = "IVF_FLAT";

    /**
     * BM25 模式的距离度量：Milvus 全文检索专用的 BM25 评分。
     */
    public static final String METRIC_BM25 = "BM25";

    /**
     * BM25 模式的索引类型：稀疏向量倒排索引。
     */
    public static final String SPARSE_INVERTED_INDEX = "SPARSE_INVERTED_INDEX";

    private final String collection;
    private final int dimension;
    private final String metricType;
    private final String indexType;
    /**
     * -- GETTER --
     *  取检索模式。
     *
     * @return {@link MilvusSettings#MODE_VECTOR} 或 {@link MilvusSettings#MODE_BM25}，非 null
     */
    private final String mode;
    /**
     * -- GETTER --
     *  取 BM25 模式的分词器名。
     *
     * @return 分词器名，非 null
     */
    private final String analyzer;

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
        this(collection, dimension, metricType, indexType, MilvusSettings.MODE_VECTOR, MilvusSettings.DEFAULT_ANALYZER);
    }

    /**
     * 创建 BM25 全文检索集合规格：集合里不存密集向量，维度仅作占位、不参与身份比较。
     *
     * @param collection 集合名称，不可为空白
     * @param analyzer   分词器名（如 {@code chinese} = jieba），不可为空白
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public MilvusCollectionSpec(String collection, String analyzer) {
        this(collection, PLACEHOLDER_DIMENSION, METRIC_BM25, SPARSE_INVERTED_INDEX,
                MilvusSettings.MODE_BM25, analyzer);
    }

    /**
     * 创建集合规格（完整参数）。
     *
     * @param collection 集合名称，不可为空白
     * @param dimension  向量维度，必须为正（BM25 模式传占位值）
     * @param metricType 距离度量，不可为空白
     * @param indexType  索引类型，不可为空白
     * @param mode       检索模式（{@link MilvusSettings#MODE_VECTOR} / {@link MilvusSettings#MODE_BM25}），不可为空白
     * @param analyzer   分词器名（仅 BM25 模式使用），不可为空白
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public MilvusCollectionSpec(String collection, int dimension, String metricType, String indexType,
            String mode, String analyzer) {
        if (StringUtils.isBlank(collection) || dimension <= 0 || StringUtils.isBlank(metricType) || StringUtils.isBlank(indexType)
                || StringUtils.isBlank(mode) || StringUtils.isBlank(analyzer)) {
            throw new IllegalArgumentException("milvus collection specification is invalid");
        }
        this.collection = collection;
        this.dimension = dimension;
        this.metricType = metricType;
        this.indexType = indexType;
        this.mode = mode;
        this.analyzer = analyzer;
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

    /**
     * 是否为 BM25 全文检索模式（此时集合里没有密集向量字段）。
     *
     * @return BM25 模式时返回 true
     */
    public boolean isBm25() {
        return MilvusSettings.MODE_BM25.equals(mode);
    }

    /**
     * 判断两个集合规格是否同源（可复用同一既有集合）。
     *
     * <p>名称、模式与度量必须一致；向量模式还要求维度相同（维度不同的向量无法相互比较），
     * BM25 模式没有维度，改要求分词器相同（分词器变了，稀疏向量的词表就不一致）。
     *
     * @param other 待比较的规格，可为 null
     * @return 同源时返回 true
     */
    public boolean sameIdentity(MilvusCollectionSpec other) {
        if (other == null || !collection.equals(other.collection) || !mode.equals(other.mode)
                || !metricType.equals(other.metricType)) {
            return false;
        }
        return isBm25() ? analyzer.equals(other.analyzer) : dimension == other.dimension;
    }

    /**
     * BM25 模式下的维度占位值：BM25 集合不存密集向量，这个值不会被使用。
     */
    private static final int PLACEHOLDER_DIMENSION = 1;

}
