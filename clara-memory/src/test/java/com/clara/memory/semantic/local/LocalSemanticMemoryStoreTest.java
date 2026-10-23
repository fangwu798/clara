package com.clara.memory.semantic.local;

import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link LocalSemanticMemoryStore} 的单元测试：余弦相似度排序、topK/minScore 过滤与按条目 / 会话删除。
 */
public class LocalSemanticMemoryStoreTest {

    private static final long NOW = 1_000_000L;

    private static MemoryEntry entry(String id, MemorySourceTypeEnum type, String conversationId,
            float[] vector, long createdAt) {
        return MemoryEntry.builder(id, type, id, "content-" + id, sha256("content-" + id), vector)
                .conversationId(conversationId)
                .createdAtEpochMillis(createdAt)
                .build();
    }

    private static SemanticMemoryQuery query(float[] vector, int topK, double minScore) {
        return SemanticMemoryQuery.builder(vector, topK, minScore, NOW).build();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", Integer.valueOf(b & 0xff)));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void searchReturnsEntriesOrderedByCosineSimilarity() {
        LocalSemanticMemoryStore store = new LocalSemanticMemoryStore(3);
        store.upsert(Arrays.asList(
                entry("a", MemorySourceTypeEnum.CONVERSATION, "conv", new float[] {1, 0, 0}, 100L),
                entry("b", MemorySourceTypeEnum.CONVERSATION, "conv", new float[] {0, 1, 0}, 200L),
                entry("c", MemorySourceTypeEnum.CONVERSATION, "conv", new float[] {0, 0, 1}, 300L)));
        List<MemoryMatch> matches = store.search(query(new float[] {1, 0, 0}, 3, 0.0));
        assertEquals(3, matches.size());
        assertEquals("a", matches.get(0).getEntry().getId());
        assertEquals(1.0, matches.get(0).getScore(), 1e-6);
        assertEquals("c", matches.get(1).getEntry().getId());
        assertEquals("b", matches.get(2).getEntry().getId());
    }

    @Test
    public void topKAndMinScoreFilterResults() {
        LocalSemanticMemoryStore store = new LocalSemanticMemoryStore(3);
        store.upsert(Arrays.asList(
                entry("a", MemorySourceTypeEnum.CONVERSATION, "conv", new float[] {1, 0, 0}, 100L),
                entry("b", MemorySourceTypeEnum.CONVERSATION, "conv", new float[] {0, 1, 0}, 200L)));
        List<MemoryMatch> top1 = store.search(query(new float[] {1, 0, 0}, 1, 0.0));
        assertEquals(1, top1.size());
        assertEquals("a", top1.get(0).getEntry().getId());

        List<MemoryMatch> highScore = store.search(query(new float[] {1, 0, 0}, 10, 0.9));
        assertEquals(1, highScore.size());
        assertEquals("a", highScore.get(0).getEntry().getId());
    }

    @Test
    public void deleteByIdRemovesSingleEntry() {
        LocalSemanticMemoryStore store = new LocalSemanticMemoryStore(2);
        store.upsert(Arrays.asList(
                entry("a", MemorySourceTypeEnum.CONVERSATION, "conv", new float[] {1, 0}, 100L),
                entry("b", MemorySourceTypeEnum.CONVERSATION, "conv", new float[] {0, 1}, 100L)));
        store.deleteById("a");
        List<MemoryMatch> matches = store.search(query(new float[] {0, 1}, 10, 0.0));
        assertEquals(1, matches.size());
        assertEquals("b", matches.get(0).getEntry().getId());
    }

    @Test
    public void deleteByConversationOnlyRemovesConversationSource() {
        LocalSemanticMemoryStore store = new LocalSemanticMemoryStore(2);
        store.upsert(Arrays.asList(
                entry("conv1", MemorySourceTypeEnum.CONVERSATION, "conv-1", new float[] {1, 0}, 100L),
                entry("conv2", MemorySourceTypeEnum.CONVERSATION, "conv-2", new float[] {0, 1}, 100L),
                entry("prompt", MemorySourceTypeEnum.PROMPT, null, new float[] {1, 1}, 100L)));
        store.deleteByConversation("conv-1");
        List<MemoryMatch> matches = store.search(query(new float[] {1, 1}, 10, 0.0));
        assertEquals(2, matches.size());
        for (MemoryMatch match : matches) {
            assertNotEquals("conv1", match.getEntry().getId());
        }
    }

    /**
     * 构造与写入校验：维度必须为正、批次与元素不得为 null、维度必须一致、同一批次 id 不得重复。
     */
    @Test
    public void validatesDimensionAndUpsertPayload() {
        assertThrows(IllegalArgumentException.class, () -> new LocalSemanticMemoryStore(0));
        assertThrows(IllegalArgumentException.class, () -> new LocalSemanticMemoryStore(-1));

        LocalSemanticMemoryStore store = new LocalSemanticMemoryStore(2);
        assertThrows(IllegalArgumentException.class, () -> store.upsert(null));
        store.upsert(Collections.<MemoryEntry>emptyList());
        assertThrows(IllegalArgumentException.class, () -> store.upsert(Collections.<MemoryEntry>singletonList(null)));
        assertThrows(com.clara.exception.ClaraException.class, () -> store.upsert(Collections.singletonList(
                entry("a", MemorySourceTypeEnum.PROMPT, null, new float[] {1, 0, 0}, 100L))));
        assertThrows(IllegalArgumentException.class, () -> store.upsert(Arrays.asList(
                entry("a", MemorySourceTypeEnum.PROMPT, null, new float[] {1, 0}, 100L),
                entry("a", MemorySourceTypeEnum.PROMPT, null, new float[] {0, 1}, 100L))));
    }

    /**
     * 检索条件：来源、会话、元数据过滤、过期条目与非法查询。
     */
    @Test
    public void appliesQueryFiltersAndRejectsInvalidQueries() {
        LocalSemanticMemoryStore store = new LocalSemanticMemoryStore(2);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("kind", "doc");
        MemoryEntry conversation = MemoryEntry.builder("c1", MemorySourceTypeEnum.CONVERSATION, "s", "body",
                        sha256("c1"), new float[] {1, 0})
                .conversationId("conv-1").createdAtEpochMillis(100L).metadata(metadata).build();
        MemoryEntry otherConversation = entry("c2", MemorySourceTypeEnum.CONVERSATION, "conv-2",
                new float[] {1, 0}, 100L);
        MemoryEntry prompt = entry("p1", MemorySourceTypeEnum.PROMPT, null, new float[] {1, 0}, 100L);
        MemoryEntry expired = MemoryEntry.builder("e1", MemorySourceTypeEnum.CONVERSATION, "s", "body",
                        sha256("e1"), new float[] {1, 0})
                .conversationId("conv-1").createdAtEpochMillis(100L)
                .expiresAtEpochMillis(200L).build();
        store.upsert(Arrays.asList(conversation, otherConversation, prompt, expired));

        Map<String, String> filters = new HashMap<>();
        filters.put("kind", "doc");
        List<MemoryMatch> filtered = store.search(SemanticMemoryQuery.builder(new float[] {1, 0}, 10, 0.0, NOW)
                .filters(filters).build());
        assertEquals(1, filtered.size());
        assertEquals("c1", filtered.get(0).getEntry().getId());

        Map<String, String> noMatch = new HashMap<>();
        noMatch.put("kind", "other");
        assertTrue(store.search(SemanticMemoryQuery.builder(new float[] {1, 0}, 10, 0.0, NOW)
                .filters(noMatch).build()).isEmpty());

        List<MemoryMatch> bySource = store.search(SemanticMemoryQuery.builder(new float[] {1, 0}, 10, 0.0, NOW)
                .sourceType(MemorySourceTypeEnum.PROMPT).build());
        assertEquals(1, bySource.size());
        assertEquals("p1", bySource.get(0).getEntry().getId());

        List<MemoryMatch> byConversation = store.search(SemanticMemoryQuery.builder(new float[] {1, 0}, 10, 0.0, NOW)
                .conversationId("conv-2").build());
        assertEquals(1, byConversation.size());
        assertEquals("c2", byConversation.get(0).getEntry().getId());

        assertThrows(IllegalArgumentException.class, () -> store.search(null));
        assertThrows(com.clara.exception.ClaraException.class,
                () -> store.search(query(new float[] {1, 0, 0}, 1, 0.0)));
    }

    /**
     * 同分同时间时按 id 稳定排序；零向量得分为 -1，不应产生 NaN。
     */
    @Test
    public void sortsTiesByIdAndHandlesZeroVectors() {
        LocalSemanticMemoryStore store = new LocalSemanticMemoryStore(2);
        store.upsert(Arrays.asList(
                entry("b", MemorySourceTypeEnum.PROMPT, null, new float[] {1, 0}, 100L),
                entry("a", MemorySourceTypeEnum.PROMPT, null, new float[] {1, 0}, 100L)));

        List<MemoryMatch> matches = store.search(query(new float[] {1, 0}, 10, 0.0));
        assertEquals("a", matches.get(0).getEntry().getId());
        assertEquals("b", matches.get(1).getEntry().getId());

        // 全零向量在 DTO 构造期就被拒绝，检索不会出现除零
        assertThrows(IllegalArgumentException.class, () -> query(new float[] {0, 0}, 1, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> entry("zero", MemorySourceTypeEnum.PROMPT, null, new float[] {0, 0}, 100L));
    }

    /**
     * 删除参数与关闭后行为：null 参数显式拒绝，关闭后所有操作快速失败。
     */
    @Test
    public void rejectsNullDeletesAndOperationsAfterClose() {
        LocalSemanticMemoryStore store = new LocalSemanticMemoryStore(2);
        assertThrows(IllegalArgumentException.class, () -> store.deleteById(null));
        assertThrows(IllegalArgumentException.class, () -> store.deleteByConversation(null));

        store.close();

        assertThrows(IllegalStateException.class, () -> store.upsert(Collections.<MemoryEntry>emptyList()));
        assertThrows(IllegalStateException.class, () -> store.search(query(new float[] {1, 0}, 1, 0.0)));
        assertThrows(IllegalStateException.class, () -> store.deleteById("a"));
        assertThrows(IllegalStateException.class, () -> store.deleteByConversation("conv"));
    }
}