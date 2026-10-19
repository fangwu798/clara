package com.clara.orchestration.context;

import com.clara.core.memory.IContextTraceSink;
import com.clara.core.message.IContent;
import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.dto.memory.ContextControlConfig;
import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import com.clara.kernel.message.delta.UserContent;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link SemanticRecallCoordinator} 单元测试：依赖缺失 / 检索关闭 / 空输入返回空，正常时按阈值过滤命中。
 *
 * <p>另盯一组「上报」：命中几条、报没报阈值与相似度——这些正是控制台观察时靠的东西，
 * 而它们对对话结果没有任何影响，只能靠测试卡住。
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
        // 查询不预过滤：阈值判定留给本类，否则上报里就只有一个数，分不清故障方向
        assertEquals(-1.0d, store.queries.get(0).getMinScore(), 0.0001d);
    }

    @Test
    public void recallReportsHitsWithThresholdAndScores() {
        RecordingTrace trace = new RecordingTrace();
        List<MemoryMatch> matches = Arrays.asList(
                new MemoryMatch(entry(MemorySourceTypeEnum.CONVERSATION, "e1", "kept-high"), 0.95),
                new MemoryMatch(entry(MemorySourceTypeEnum.CONVERSATION, "e2", "dropped"), 0.2));
        SemanticRecallCoordinator coordinator = new SemanticRecallCoordinator(
                text -> new float[] {1f, 0f}, new RecordingStore(matches), trace);

        coordinator.recall("c1", "query text", control(3, 0.5));

        assertEquals(Collections.singletonList("semantic_recall"), trace.sources);
        assertEquals(Collections.singletonList(1), trace.hits);
        // 明细首行分开报「召回几条 / 过阈值几条」：未命中时才能分清是库里没数据还是分数不够
        List<String> details = trace.details.get(0);
        assertEquals(2, details.size());
        assertTrue(details.get(0).contains("召回 2 条"));
        assertTrue(details.get(0).contains("过阈值 1 条"));
        assertTrue(details.get(0).contains("阈值 0.50"));
        assertTrue(details.get(0).contains("最高分 0.950"));
        assertTrue(details.get(1).contains("score=0.950"));
        assertTrue(details.get(1).contains("kept-high"));
    }

    @Test
    public void recallDistinguishesEmptyStoreFromBelowThreshold() {
        RecordingTrace empty = new RecordingTrace();
        new SemanticRecallCoordinator(text -> new float[] {1f, 0f},
                new RecordingStore(Collections.<MemoryMatch>emptyList()), empty)
                .recall("c1", "query text", control(3, 0.5));
        assertTrue(empty.details.get(0).get(0).contains("召回 0 条"));
        assertTrue(empty.details.get(0).get(0).contains("语义库里没有本会话可检索的记录"));

        RecordingTrace low = new RecordingTrace();
        new SemanticRecallCoordinator(text -> new float[] {1f, 0f},
                new RecordingStore(Collections.singletonList(
                        new MemoryMatch(entry(MemorySourceTypeEnum.CONVERSATION, "e1", "low"), 0.1))), low)
                .recall("c1", "query text", control(3, 0.5));
        assertTrue(low.details.get(0).get(0).contains("召回 1 条"));
        assertTrue(low.details.get(0).get(0).contains("过阈值 0 条"));
        assertTrue(low.details.get(0).get(0).contains("最高分 0.100"));
    }

    @Test
    public void recallReportsMissWithZeroHits() {
        RecordingTrace trace = new RecordingTrace();
        SemanticRecallCoordinator coordinator = new SemanticRecallCoordinator(
                text -> new float[] {1f, 0f},
                new RecordingStore(Collections.singletonList(
                        new MemoryMatch(entry(MemorySourceTypeEnum.CONVERSATION, "e1", "low"), 0.1))),
                trace);

        assertTrue(coordinator.recall("c1", "query text", control(3, 0.5)).isEmpty());

        assertEquals(Collections.singletonList(0), trace.hits);
    }

    @Test
    public void recallDropsHitsThatCurrentTurnAlreadyCarries() {
        RecordingTrace trace = new RecordingTrace();
        // 条目的入库文本与「本轮要下发的消息」一字不差：模型本来就看得到，不该再当补充信息塞一遍
        MemoryMatch duplicated = new MemoryMatch(entry(MemorySourceTypeEnum.CONVERSATION, "e1",
                "user: 请帮我分析一下 PackageRuleDescriptionService"), 0.97);
        SemanticRecallCoordinator coordinator = new SemanticRecallCoordinator(text -> new float[] {1f, 0f},
                new RecordingStore(Collections.singletonList(duplicated)), trace);

        List<String> knowledge = coordinator.recall("c1", "请帮我分析一下 PackageRuleDescriptionService",
                control(3, 0.5), Collections.<IContent>singletonList(
                        new UserContent("请帮我分析一下 PackageRuleDescriptionService")));

        assertTrue(knowledge.isEmpty());
        assertEquals(Collections.singletonList(0), trace.hits);
        // 上报要写明是「已在本轮上下文」丢的，而不是「分数不够」——两者的排查方向完全相反
        assertTrue(trace.details.get(0).get(0).contains("全部已在本轮上下文，已丢弃"));
        assertTrue(trace.details.get(0).get(1).contains("丢弃 score=0.970"));
    }

    @Test
    public void recallDropsOnlyTheHitsVisibleInCurrentTurn() {
        RecordingTrace trace = new RecordingTrace();
        List<MemoryMatch> matches = Arrays.asList(
                new MemoryMatch(entry(MemorySourceTypeEnum.CONVERSATION, "e1", "user: 另一个问题"), 0.95),
                new MemoryMatch(entry(MemorySourceTypeEnum.CONVERSATION, "e2", "tool: 旧的工具结果"), 0.91));
        SemanticRecallCoordinator coordinator = new SemanticRecallCoordinator(text -> new float[] {1f, 0f},
                new RecordingStore(matches), trace);

        List<String> knowledge = coordinator.recall("c1", "另一个问题", control(3, 0.5),
                Collections.<IContent>singletonList(new UserContent("另一个问题")));

        assertEquals(Arrays.asList("tool: 旧的工具结果"), knowledge);
        List<String> details = trace.details.get(0);
        assertEquals(3, details.size());
        assertTrue(details.get(0).contains("召回 2 条"));
        assertTrue(details.get(0).contains("丢弃 1 条（已在本轮上下文）"));
        assertTrue(details.get(0).contains("过阈值 1 条"));
        assertTrue(details.get(1).contains("score=0.910"));
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
        private final List<SemanticMemoryQuery> queries = new ArrayList<>();

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

    /** 记录上报内容的观测口：断言「打了哪些点、报了什么」用。 */
    private static final class RecordingTrace implements IContextTraceSink {

        private final List<String> sources = new ArrayList<>();
        private final List<Integer> hits = new ArrayList<>();
        private final List<List<String>> details = new ArrayList<>();

        @Override
        public void onTrace(String source, int hitCount, long elapsedMillis, List<String> rendered) {
            sources.add(source);
            hits.add(hitCount);
            details.add(new ArrayList<>(rendered));
        }
    }
}
