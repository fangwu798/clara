package com.clara.memory.persistence;

import com.clara.core.message.IContent;
import com.clara.enums.interaction.HistoryClearScopeEnum;
import com.clara.kernel.message.delta.UserContent;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 补充覆盖 {@link BoundedCachingConversationStore} 的参数校验与转发语义。
 *
 * <p>缓存包装器本身没有复杂算法，风险集中在「底层失败时缓存是否被错误保留」，
 * 因此这里重点验证 clear / deleteChatMessages / close 都会同步清空缓存。
 */
public class BoundedCachingConversationStoreExtraTest {

    private static final class RecordingStore implements ConversationStore {
        private final InMemoryConversationStore delegate = new InMemoryConversationStore();
        int clearCalls = 0;
        int deleteCalls = 0;
        int closeCalls = 0;
        boolean returnNull;

        @Override
        public ConversationSnapshot loadSnapshot(ConversationKey key) {
            return returnNull ? null : delegate.loadSnapshot(key);
        }

        @Override
        public void append(ConversationKey key, List<IContent> messages, long expectedVersion) {
            delegate.append(key, messages, expectedVersion);
        }

        @Override
        public void clear(ConversationKey key) {
            clearCalls++;
            delegate.clear(key);
        }

        @Override
        public int deleteChatMessages(HistoryClearScopeEnum scope, int count) {
            deleteCalls++;
            return delegate.deleteChatMessages(scope, count);
        }

        @Override
        public void close() {
            closeCalls++;
            delegate.close();
        }
    }

    @Test
    public void rejectsInvalidConstructionArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new BoundedCachingConversationStore(null, 1, 1));
        RecordingStore delegate = new RecordingStore();
        assertThrows(IllegalArgumentException.class,
                () -> new BoundedCachingConversationStore(delegate, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new BoundedCachingConversationStore(delegate, 1, 0));
    }

    @Test
    public void rejectsNullKeyOnEveryOperation() {
        BoundedCachingConversationStore store =
                new BoundedCachingConversationStore(new RecordingStore(), 2, 2);
        assertThrows(IllegalArgumentException.class, () -> store.loadSnapshot(null));
        assertThrows(IllegalArgumentException.class,
                () -> store.append(null, Collections.singletonList(new UserContent("u")), 0L));
        assertThrows(IllegalArgumentException.class, () -> store.clear(null));
    }

    @Test
    public void clearInvalidatesCachedSnapshot() {
        RecordingStore delegate = new RecordingStore();
        BoundedCachingConversationStore store = new BoundedCachingConversationStore(delegate, 4, 10);
        ConversationKey key = new ConversationKey("conv-1", "p", "m");
        store.append(key, Collections.singletonList(new UserContent("hello")), 0L);
        // 先把快照放进缓存，再 clear，确保后续读到的是底层的新状态。
        assertEquals(1, store.loadSnapshot(key).messages().size());

        store.clear(key);
        assertEquals(1, delegate.clearCalls);
        assertTrue(store.loadSnapshot(key).messages().isEmpty());
    }

    @Test
    public void deleteChatMessagesForwardsAndClearsCache() {
        RecordingStore delegate = new RecordingStore();
        BoundedCachingConversationStore store = new BoundedCachingConversationStore(delegate, 4, 10);
        ConversationKey key = new ConversationKey("conv-1", "p", "m");
        store.append(key, Collections.singletonList(new UserContent("hello")), 0L);
        assertEquals(1, store.loadSnapshot(key).messages().size());

        assertEquals(1, store.deleteChatMessages(HistoryClearScopeEnum.ALL, 0));
        assertEquals(1, delegate.deleteCalls);
        assertTrue(store.loadSnapshot(key).messages().isEmpty());
    }

    @Test
    public void closeForwardsAndClearsCache() {
        RecordingStore delegate = new RecordingStore();
        BoundedCachingConversationStore store = new BoundedCachingConversationStore(delegate, 4, 10);
        store.close();
        assertEquals(1, delegate.closeCalls);
    }

    @Test
    public void snapshotLargerThanMessageLimitIsNotCached() {
        RecordingStore delegate = new RecordingStore();
        BoundedCachingConversationStore store = new BoundedCachingConversationStore(delegate, 4, 1);
        ConversationKey key = new ConversationKey("conv-1", "p", "m");
        store.append(key, Collections.singletonList(new UserContent("hello")), 0L);

        // 超过单会话缓存上限时仍返回给调用方，但下次读取会重新访问底层。
        assertEquals(1, store.loadSnapshot(key).messages().size());
        assertEquals(1, store.loadSnapshot(key).messages().size());
    }

    @Test
    public void nullDelegateSnapshotIsReturnedAsIs() {
        RecordingStore delegate = new RecordingStore();
        delegate.returnNull = true;
        BoundedCachingConversationStore store = new BoundedCachingConversationStore(delegate, 4, 10);
        assertEquals(null, store.loadSnapshot(new ConversationKey("conv-1", "p", "m")));
    }
}