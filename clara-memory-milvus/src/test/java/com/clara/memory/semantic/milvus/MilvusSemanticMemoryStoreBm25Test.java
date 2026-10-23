package com.clara.memory.semantic.milvus;

import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link MilvusSemanticMemoryStore} 在 BM25 模式下的单元测试（用记录型假后端，不连真实 Milvus）：
 * 入库不再校验向量维度、检索改看原始文本。
 *
 * <p>为什么单独测这两点：BM25 集合里没有向量，若仍按维度校验，历史记录会被整批拒写；
 * 若检索漏传原始文本，后端只能返回空结果——两种情况都不会报错，只会「静默什么都招不回来」。
 */
public class MilvusSemanticMemoryStoreBm25Test {

    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void upsertSkipsVectorDimensionCheckInBm25Mode() {
        MilvusCollectionSpec spec = new MilvusCollectionSpec("clara_conversations", "chinese");
        RecordingBackend backend = new RecordingBackend(spec);
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec, backend);
        MemoryEntry entry = entry("bm25-1", new float[] {1f, 2f, 3f});
        store.upsert(Collections.singletonList(entry));
        assertEquals(1, backend.upserted.size());
        assertEquals("bm25-1", backend.upserted.get(0).getId());
    }

    @Test
    public void searchRequiresRawQueryText() {
        MilvusCollectionSpec spec = new MilvusCollectionSpec("clara_conversations", "chinese");
        RecordingBackend backend = new RecordingBackend(spec);
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec, backend);
        try {
            store.search(query(null));
            throw new AssertionError("bm25 search without query text should be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue("未传正文就不该打到后端", backend.queries.isEmpty());
        }
    }

    @Test
    public void blankQueryTextIsTreatedAsMissing() {
        MilvusCollectionSpec spec = new MilvusCollectionSpec("clara_conversations", "chinese");
        RecordingBackend backend = new RecordingBackend(spec);
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec, backend);
        try {
            store.search(query("   "));
            throw new AssertionError("blank query text should be treated as missing");
        } catch (IllegalArgumentException expected) {
            assertTrue("blank text must be rejected before querying the backend", backend.queries.isEmpty());
            // 期望：空白文本被归一成 null，仍按「缺正文」拒绝
        }
    }

    @Test
    public void rawQueryTextIsForwardedToBackend() {
        MilvusCollectionSpec spec = new MilvusCollectionSpec("clara_conversations", "chinese");
        RecordingBackend backend = new RecordingBackend(spec);
        backend.results = Collections.singletonList(
                new MemoryMatch(entry("bm25-2", new float[] {1f}), 0.82d));
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec, backend);
        List<MemoryMatch> matches = store.search(query("套餐算费特殊规则怎么透传"));
        assertEquals(1, matches.size());
        assertEquals(0.82d, matches.get(0).getScore(), 1e-9);
        assertEquals(1, backend.queries.size());
        assertEquals("套餐算费特殊规则怎么透传", backend.queries.get(0).getQuery().getQueryText());
    }

    @Test
    public void entryWithoutQueryTextKeepsNull() {
        assertNull(SemanticMemoryQuery.builder(new float[] {1f}, 3, -1.0d, System.currentTimeMillis())
                .sourceType(MemorySourceTypeEnum.CONVERSATION)
                .conversationId("conv-1")
                .build().getQueryText());
    }

    private static SemanticMemoryQuery query(String queryText) {
        return SemanticMemoryQuery.builder(new float[] {1f}, 3, -1.0d, System.currentTimeMillis())
                .sourceType(MemorySourceTypeEnum.CONVERSATION)
                .conversationId("conv-1")
                .queryText(queryText)
                .build();
    }

    private static MemoryEntry entry(String id, float[] vector) {
        return MemoryEntry.builder(id, MemorySourceTypeEnum.CONVERSATION, "conv-1", "content-" + id, HASH, vector)
                .conversationId("conv-1")
                .createdAtEpochMillis(System.currentTimeMillis())
                .metadata(Collections.<String, String>emptyMap())
                .build();
    }

    /**
     * 记录型假后端：只记录调用参数、回放预置结果，用于在没有 Milvus 的环境里验证存储层行为。
     */
    private static final class RecordingBackend implements MilvusBackend {

        private final MilvusCollectionSpec spec;
        private final List<MilvusBackendQuery> queries = new ArrayList<>();
        private List<MemoryEntry> upserted = Collections.emptyList();
        private List<MemoryMatch> results = Collections.emptyList();

        RecordingBackend(MilvusCollectionSpec spec) {
            this.spec = spec;
        }

        @Override
        public MilvusCollectionSpec ensureCollection(MilvusCollectionSpec expected) {
            return spec;
        }

        @Override
        public void upsert(String collection, List<MemoryEntry> entries) {
            this.upserted = new ArrayList<>(entries);
        }

        @Override
        public List<MemoryMatch> search(String collection, MilvusBackendQuery query) {
            queries.add(query);
            return new ArrayList<>(results);
        }

        @Override
        public void deleteById(String collection, String entryId) {
        }

        @Override
        public void deleteByConversation(String collection, String conversationId) {
        }

        @Override
        public void dropCollection(String collection) {
        }

        @Override
        public void close() {
        }
    }
}
