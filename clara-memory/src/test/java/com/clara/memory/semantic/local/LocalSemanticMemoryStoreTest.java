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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * {@link LocalSemanticMemoryStore} 的单元测试：余弦相似度排序、topK/minScore 过滤与按条目 / 会话删除。
 */
public class LocalSemanticMemoryStoreTest {

    private static final long NOW = 1_000_000L;

    private static MemoryEntry entry(String id, MemorySourceTypeEnum type, String conversationId,
            float[] vector, long createdAt) {
        return new MemoryEntry(id, type, id, conversationId, "content-" + id, sha256("content-" + id),
                vector, createdAt, 0L, null);
    }

    private static SemanticMemoryQuery query(float[] vector, int topK, double minScore) {
        return new SemanticMemoryQuery(null, null, vector, topK, minScore, NOW, null);
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
            assertFalse("conv1".equals(match.getEntry().getId()));
        }
    }
}
