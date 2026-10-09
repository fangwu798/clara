package com.clara.orchestration.context;

import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.dto.memory.ContextControlConfig;
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
import static org.junit.Assert.assertTrue;

/**
 * {@link SemanticRecallCoordinator} 单元测试：依赖缺失 / 检索关闭 / 空输入返回空，正常时按阈值过滤命中。
 */
public class SemanticRecallCoordinatorTest {

    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void recallReturnsEmptyWhenDependenciesMissing() {
        ContextControlConfig control = control(3, 0.5);
        assertTrue(new SemanticRecallCoordinator(null, store(Collections.<MemoryMatch>emptyList()))
                .recall("c1", "query", control).isEmpty());
        assertTrue(new SemanticRecallCoordinator(text -> new float[] {1f}, null)
                .recall("c1", "query", control).isEmpty());
    }

    @Test
    public void recallReturnsEmptyWhenRetrievalDisabledOrInputBlank() {
        SemanticRecallCoordinator coordinator = new SemanticRecallCoordinator(
                text -> new float[] {1f}, store(Collections.singletonList(
                        new MemoryMatch(entry(MemorySourceTypeEnum.CONVERSATION, "e1", "knowledge"), 0.9))));

        assertTrue(coordinator.recall("c1", "query", control(0, 0.5)).isEmpty());
        assertTrue(coordinator.recall("c1", "   ", control(3, 0.5)).isEmpty());
        assertTrue(coordinator.recall("c1", null, control(3, 0.5)).isEmpty());
    }

    @Test
    public void recallFiltersByThresholdAndCollectsContent() {
        List<MemoryMatch> matches = Arrays.asList(
                new MemoryMatch(entry(MemorySourceTypeEnum.CONVERSATION, "e1", "kept-high"), 0.95),
                new MemoryMatch(entry(MemorySourceTypeEnum.CONVERSATION, "e2", "kept-equal"), 0.5),
                new MemoryMatch(entry(MemorySourceTypeEnum.CONVERSATION, "e3", "dropped"), 0.2));
        RecordingStore store = new RecordingStore(matches);
        SemanticRecallCoordinator coordinator = new SemanticRecallCoordinator(
                text -> new float[] {1f, 0f}, store);

        List<String> knowledge = coordinator.recall("c1", "query text", control(3, 0.5));

        assertEquals(Arrays.asList("kept-high", "kept-equal"), knowledge);
        // 检索以当前输入构造查询
        assertEquals(1, store.queries.size());
        assertEquals(MemorySourceTypeEnum.CONVERSATION, store.queries.get(0).getSourceType());
        assertEquals("c1", store.queries.get(0).getConversationId());
        assertEquals(3, store.queries.get(0).getTopK());
    }

    // ---- helpers -----------------------------------------------------------

    private static ContextControlConfig control(int retrievalCount, double threshold) {
        return new ContextControlConfig(-1L, -1, -1L, 80, false, false, retrievalCount, threshold);
    }

    private static MemoryEntry entry(MemorySourceTypeEnum sourceType, String id, String content) {
        return new MemoryEntry(id, sourceType, id, "c1", content, HASH,
                new float[] {1f, 0f}, 0L, 0L, null);
    }

    private static ISemanticMemoryStore store(final List<MemoryMatch> matches) {
        return new RecordingStore(matches);
    }

    private static final class RecordingStore implements ISemanticMemoryStore {

        private final List<MemoryMatch> matches;
        private final List<SemanticMemoryQuery> queries = new ArrayList<SemanticMemoryQuery>();

        RecordingStore(List<MemoryMatch> matches) {
            this.matches = matches;
        }

        @Override
        public void upsert(List<MemoryEntry> entries) {
        }

        @Override
        public List<MemoryMatch> search(SemanticMemoryQuery query) {
            queries.add(query);
            return matches;
        }

        @Override
        public void deleteById(String entryId) {
        }

        @Override
        public void deleteByConversation(String conversationId) {
        }

        @Override
        public void close() {
        }
    }
}
