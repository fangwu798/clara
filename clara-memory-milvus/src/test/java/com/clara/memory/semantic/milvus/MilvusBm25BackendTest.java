package com.clara.memory.semantic.milvus;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link MilvusBm25Backend} 的分数换算单元测试。
 *
 * <p>为什么要换算：BM25 原始分没有上界（相关文档实测 4~7 分），而上层的召回阈值
 * （默认 0.70）是按 0~1 的相似度设定的。换算必须单调且落在 (0,1)，否则阈值要么永远过不了、
 * 要么全部命中。
 */
public class MilvusBm25BackendTest {

    @Test
    public void nonPositiveScoreMapsToZero() {
        assertEquals(0d, MilvusBm25Backend.toSimilarity(0d), 1e-9);
        assertEquals(0d, MilvusBm25Backend.toSimilarity(-1.5d), 1e-9);
    }

    @Test
    public void scoreIsSqueezedIntoUnitInterval() {
        assertEquals(0.5d, MilvusBm25Backend.toSimilarity(1d), 1e-9);
        double huge = MilvusBm25Backend.toSimilarity(1000000d);
        assertTrue("再大的 BM25 分也不能到 1", huge < 1d);
        assertTrue(huge > 0.99d);
    }

    @Test
    public void scoreIsMonotonic() {
        double previous = -1d;
        double[] scores = {0.5d, 1d, 2.33d, 4d, 7.4d, 50d};
        for (double score : scores) {
            double similarity = MilvusBm25Backend.toSimilarity(score);
            assertTrue("分数 " + score + " 的相似度应高于更低分者", similarity > previous);
            previous = similarity;
        }
    }

    @Test
    public void defaultThresholdSeparatesRelevantFromIrrelevant() {
        // 阈值 0.70 约等于原始分 2.33：实测相关文档 4.62 分（0.82）能过，无关文档 1.28 分（0.56）过不了
        assertTrue(MilvusBm25Backend.toSimilarity(4.62d) > 0.70d);
        assertTrue(MilvusBm25Backend.toSimilarity(1.28d) < 0.70d);
    }
}
