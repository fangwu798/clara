package com.clara.memory.semantic.milvus;

import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link MilvusSemanticMemoryStore} 的边界行为测试（记录型假后端，不连真实 Milvus）。
 *
 * <p>为什么这些分支必须测：存储层是「上层编排」与「Milvus SDK」之间的唯一校验点。
 * 校验漏一项不会报错，只会让坏数据进库、或让召回结果顺序错乱——线上表现为「召回的总是旧消息」，
 * 很难从日志反推。这里把每条拒绝规则和排序规则都钉死。
 */
public class MilvusSemanticMemoryStoreTest {

    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void rejectsNullConstructionArguments() {
        try {
            new MilvusSemanticMemoryStore(null, new FakeBackend(spec()));
            fail("spec 为空应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：构造参数校验
        }
        try {
            new MilvusSemanticMemoryStore(spec(), null);
            fail("backend 为空应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：构造参数校验
        }
    }

    @Test
    public void constructorRejectsBackendWithDifferentIdentity() {
        MilvusCollectionSpec expected = spec();
        FakeBackend backend = new FakeBackend(new MilvusCollectionSpec("clara_conversations", 8));
        try {
            new MilvusSemanticMemoryStore(expected, backend);
            fail("集合身份不一致必须拒绝，否则检索维度对不上");
        } catch (ClaraException expectedFailure) {
            assertEquals(ErrorCodes.SEMANTIC_MEMORY_CONFIG_MISMATCH, expectedFailure.getError().getCode());
        }
    }

    @Test
    public void upsertValidatesInputAndDeduplicatesIds() {
        FakeBackend backend = new FakeBackend(spec());
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec(), backend);

        try {
            store.upsert(null);
            fail("null 列表应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
        store.upsert(Collections.<MemoryEntry>emptyList());
        assertEquals("空列表不应打到后端", 0, backend.upsertCalls.get());

        try {
            store.upsert(Arrays.asList(entry("a", 3), null));
            fail("列表含 null 应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
        try {
            store.upsert(Arrays.asList(entry("a", 3), entry("a", 3)));
            fail("重复 id 应拒绝，否则同一条记录会被写两次");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("duplicate"));
        }
        try {
            store.upsert(Collections.singletonList(entry("b", 2)));
            fail("向量维度与集合不一致应拒绝");
        } catch (ClaraException expected) {
            // 期望：配置不匹配
        }
        assertEquals("被拒绝的请求都不该打到后端", 0, backend.upsertCalls.get());
    }

    @Test
    public void upsertWrapsBackendFailure() {
        FakeBackend backend = new FakeBackend(spec());
        backend.upsertFailure = new MilvusBackendException("boom", true);
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec(), backend);
        try {
            store.upsert(Collections.singletonList(entry("a", 3)));
            fail("后端失败应转成领域错误");
        } catch (ClaraException failure) {
            assertTrue("可重试标志应透传", failure.getError().isRetryable());
        }
    }

    @Test
    public void searchRejectsInvalidQuery() {
        FakeBackend backend = new FakeBackend(spec());
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec(), backend);
        try {
            store.search(null);
            fail("null 查询应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
        try {
            store.search(query(new float[] {1f, 2f}, 5, 0d));
            fail("查询向量维度不一致应拒绝");
        } catch (ClaraException expected) {
            // 期望：配置不匹配
        }
        assertEquals("非法查询不该打到后端", 0, backend.searchCalls.get());
    }

    @Test
    public void searchFiltersByScoreSortsAndTruncatesToTopK() {
        FakeBackend backend = new FakeBackend(spec());
        backend.results = Arrays.asList(
                new MemoryMatch(entry("old-high", 3, 100L), 0.9d),
                new MemoryMatch(entry("noise", 3, 100L), 0.1d),
                new MemoryMatch(entry("new-high", 3, 300L), 0.9d),
                null,
                new MemoryMatch(entry("mid", 3, 200L), 0.7d));
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec(), backend);

        List<MemoryMatch> matches = store.search(query(new float[] {1f, 2f, 3f}, 3, 0.5d));

        assertEquals("低分与 null 应被过滤，topK 应裁剪", 3, matches.size());
        assertEquals("同分时新记录在前", "new-high", matches.get(0).getEntry().getId());
        assertEquals("old-high", matches.get(1).getEntry().getId());
        assertEquals("mid", matches.get(2).getEntry().getId());
        try {
            matches.add(matches.get(0));
            fail("返回结果应为只读");
        } catch (UnsupportedOperationException expected) {
            // 期望：只读视图
        }
    }

    @Test
    public void searchTreatsNullBackendResultAsEmpty() {
        FakeBackend backend = new FakeBackend(spec());
        backend.results = null;
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec(), backend);
        assertTrue(store.search(query(new float[] {1f, 2f, 3f}, 5, 0d)).isEmpty());
    }

    @Test
    public void searchWrapsBackendFailure() {
        FakeBackend backend = new FakeBackend(spec());
        backend.searchFailure = new MilvusBackendException("boom", false);
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec(), backend);
        try {
            store.search(query(new float[] {1f, 2f, 3f}, 5, 0d));
            fail("后端失败应转成领域错误");
        } catch (ClaraException failure) {
            assertFalse("不可重试标志应透传", failure.getError().isRetryable());
        }
    }

    @Test
    public void deleteOperationsValidateAndForward() {
        FakeBackend backend = new FakeBackend(spec());
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec(), backend);
        try {
            store.deleteById(null);
            fail("null 条目标识应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
        try {
            store.deleteByConversation(null);
            fail("null 会话标识应拒绝");
        } catch (IllegalArgumentException expected) {
            // 期望：参数校验
        }
        store.deleteById("a");
        store.deleteByConversation("conv-1");
        assertEquals(Collections.singletonList("a"), backend.deletedIds);
        assertEquals(Collections.singletonList("conv-1"), backend.deletedConversations);

        backend.deleteFailure = new MilvusBackendException("boom", true);
        try {
            store.deleteById("b");
            fail("后端失败应转成领域错误");
        } catch (ClaraException expected) {
            // 期望：领域错误
        }
        try {
            store.deleteByConversation("conv-2");
            fail("后端失败应转成领域错误");
        } catch (ClaraException expected) {
            // 期望：领域错误
        }
    }

    @Test
    public void closeIsIdempotentAndBlocksFurtherUse() {
        FakeBackend backend = new FakeBackend(spec());
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec(), backend);
        store.close();
        store.close();
        assertEquals("重复关闭不应重复释放后端", 1, backend.closeCalls.get());
        try {
            store.upsert(Collections.singletonList(entry("a", 3)));
            fail("关闭后写入应拒绝");
        } catch (IllegalStateException expected) {
            // 期望：存储已关闭
        }
        try {
            store.search(query(new float[] {1f, 2f, 3f}, 5, 0d));
            fail("关闭后检索应拒绝");
        } catch (IllegalStateException expected) {
            // 期望：存储已关闭
        }
    }

    @Test
    public void closeWrapsBackendFailure() {
        FakeBackend backend = new FakeBackend(spec());
        backend.closeFailure = new RuntimeException("close failed");
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec(), backend);
        try {
            store.close();
            fail("关闭失败应转成领域错误");
        } catch (ClaraException expected) {
            // 期望：领域错误
        }
    }

    @Test
    public void searchConvertsBackendLinkageErrorToDomainFailure() {
        FakeBackend backend = new FakeBackend(spec());
        backend.searchLinkageFailure = new NoClassDefFoundError(
                "io/milvus/shaded/io/grpc/internal/NoopClientStream");
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec(), backend);

        try {
            store.search(query(new float[] {1f, 2f, 3f}, 5, 0d));
            fail("后端类加载失败应按后端不可用处理");
        } catch (ClaraException expected) {
            // 期望：领域错误，而不是 Error 穿透到对话主循环
        }
    }

    @Test
    public void closeConvertsBackendLinkageErrorToDomainFailure() {
        FakeBackend backend = new FakeBackend(spec());
        backend.closeLinkageFailure = new NoClassDefFoundError(
                "io/milvus/shaded/io/grpc/internal/NoopClientStream");
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec(), backend);

        try {
            store.close();
            fail("关闭时的类加载失败应按后端不可用处理");
        } catch (ClaraException expected) {
            // 期望：领域错误
        }
    }

    @Test
    public void searchTruncatesWhenHitsExceedTopK() {
        FakeBackend backend = new FakeBackend(spec());
        backend.results = Arrays.asList(
                new MemoryMatch(entry("high", 3, 100L), 0.9d),
                new MemoryMatch(entry("low", 3, 100L), 0.8d));
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec(), backend);

        List<MemoryMatch> matches = store.search(query(new float[] {1f, 2f, 3f}, 1, 0d));

        assertEquals("topK=1 时只保留最高分", 1, matches.size());
        assertEquals("high", matches.get(0).getEntry().getId());
    }

    @Test
    public void searchBreaksScoreAndTimeTiesById() {
        FakeBackend backend = new FakeBackend(spec());
        backend.results = Arrays.asList(
                new MemoryMatch(entry("b", 3, 100L), 0.8d),
                new MemoryMatch(entry("a", 3, 100L), 0.8d));
        MilvusSemanticMemoryStore store = new MilvusSemanticMemoryStore(spec(), backend);

        List<MemoryMatch> matches = store.search(query(new float[] {1f, 2f, 3f}, 5, 0d));

        assertEquals("同分同时刻必须按 id 稳定排序，否则每次召回顺序都可能变", "a",
                matches.get(0).getEntry().getId());
        assertEquals("b", matches.get(1).getEntry().getId());
    }
    private static MilvusCollectionSpec spec() {
        return new MilvusCollectionSpec("clara_conversations", 3);
    }

    private static SemanticMemoryQuery query(float[] vector, int topK, double minScore) {
        return SemanticMemoryQuery.builder(vector, topK, minScore, System.currentTimeMillis())
                .sourceType(MemorySourceTypeEnum.CONVERSATION)
                .conversationId("conv-1")
                .build();
    }

    private static MemoryEntry entry(String id, int dimension) {
        return entry(id, dimension, System.currentTimeMillis());
    }

    private static MemoryEntry entry(String id, int dimension, long createdAt) {
        float[] vector = new float[dimension];
        Arrays.fill(vector, 1f);
        return MemoryEntry.builder(id, MemorySourceTypeEnum.CONVERSATION, "conv-1",
                        "content-" + id, HASH, vector)
                .conversationId("conv-1")
                .createdAtEpochMillis(createdAt)
                .metadata(Collections.<String, String>emptyMap())
                .build();
    }

    /**
     * 可编排失败与返回值的假后端：验证存储层是否按预期转发与包装。
     */
    private static final class FakeBackend implements MilvusBackend {

        private final MilvusCollectionSpec actual;
        private final AtomicInteger upsertCalls = new AtomicInteger();
        private final AtomicInteger searchCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final List<String> deletedIds = new ArrayList<>();
        private final List<String> deletedConversations = new ArrayList<>();
        private List<MemoryMatch> results = Collections.emptyList();
        private RuntimeException upsertFailure;
        private RuntimeException searchFailure;
        private RuntimeException deleteFailure;
        private RuntimeException closeFailure;
        private LinkageError searchLinkageFailure;
        private LinkageError closeLinkageFailure;

        private FakeBackend(MilvusCollectionSpec actual) {
            this.actual = actual;
        }

        @Override
        public MilvusCollectionSpec ensureCollection(MilvusCollectionSpec expected) {
            return actual;
        }

        @Override
        public void upsert(String collection, List<MemoryEntry> entries) {
            upsertCalls.incrementAndGet();
            if (upsertFailure != null) {
                throw upsertFailure;
            }
        }

        @Override
        public List<MemoryMatch> search(String collection, MilvusBackendQuery query) {
            searchCalls.incrementAndGet();
            if (searchFailure != null) {
                throw searchFailure;
            }
            if (searchLinkageFailure != null) {
                throw searchLinkageFailure;
            }
            return results;
        }

        @Override
        public void deleteById(String collection, String entryId) {
            if (deleteFailure != null) {
                throw deleteFailure;
            }
            deletedIds.add(entryId);
        }

        @Override
        public void deleteByConversation(String collection, String conversationId) {
            if (deleteFailure != null) {
                throw deleteFailure;
            }
            deletedConversations.add(conversationId);
        }

        @Override
        public void dropCollection(String collection) {
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            if (closeFailure != null) {
                throw closeFailure;
            }
            if (closeLinkageFailure != null) {
                throw closeLinkageFailure;
            }
        }
    }
}
