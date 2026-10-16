package com.clara.memory.semantic.milvus;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link MilvusCollectionSpec} 的单元测试：向量模式按维度判身份、BM25 模式按分词器判身份，
 * 两种模式互不兼容（切换只能重建集合）。
 *
 * <p>为什么身份判定值得专门测：它决定启动时「能不能复用既有集合」。判宽了会拿向量集合去跑全文检索
 * （检索恒为空），判严了每次启动都重建集合（丢历史记录）。
 */
public class MilvusCollectionSpecTest {

    @Test
    public void vectorSpecUsesDimensionAsIdentity() {
        MilvusCollectionSpec spec = new MilvusCollectionSpec("clara_conversations", 1024);
        assertFalse(spec.isBm25());
        assertEquals(MilvusSettings.MODE_VECTOR, spec.getMode());
        assertTrue("索引类型不参与身份比较",
                spec.sameIdentity(new MilvusCollectionSpec("clara_conversations", 1024, "COSINE", "HNSW")));
        assertFalse("维度不同的向量无法相互比较",
                spec.sameIdentity(new MilvusCollectionSpec("clara_conversations", 768)));
        assertFalse("集合名不同不能共用",
                spec.sameIdentity(new MilvusCollectionSpec("other_conversations", 1024)));
        assertFalse("距离度量不同不能共用",
                spec.sameIdentity(new MilvusCollectionSpec("clara_conversations", 1024, "L2")));
        assertFalse("向量集合不能与 BM25 集合共用",
                spec.sameIdentity(new MilvusCollectionSpec("clara_conversations", "chinese")));
        assertFalse(spec.sameIdentity(null));
    }

    @Test
    public void bm25SpecUsesAnalyzerAsIdentity() {
        MilvusCollectionSpec spec = new MilvusCollectionSpec("clara_conversations", "chinese");
        assertTrue(spec.isBm25());
        assertEquals(MilvusSettings.MODE_BM25, spec.getMode());
        assertEquals(MilvusCollectionSpec.METRIC_BM25, spec.getMetricType());
        assertEquals(MilvusCollectionSpec.SPARSE_INVERTED_INDEX, spec.getIndexType());
        assertTrue(spec.sameIdentity(new MilvusCollectionSpec("clara_conversations", "chinese")));
        assertFalse("分词器变了，稀疏向量的词表就对不上新查询的词",
                spec.sameIdentity(new MilvusCollectionSpec("clara_conversations", "standard")));
        assertFalse(spec.sameIdentity(new MilvusCollectionSpec("other_conversations", "chinese")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankAnalyzerRejected() {
        new MilvusCollectionSpec("clara_conversations", "   ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankCollectionRejected() {
        new MilvusCollectionSpec("   ", 1024);
    }
}
