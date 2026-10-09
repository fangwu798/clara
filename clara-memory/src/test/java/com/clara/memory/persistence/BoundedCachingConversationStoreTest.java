package com.clara.memory.persistence;

import com.clara.core.message.IContent;
import com.clara.kernel.message.delta.UserContent;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * {@link BoundedCachingConversationStore} 的单元测试：缓存命中、append 后失效与按会话容量淘汰。
 */
public class BoundedCachingConversationStoreTest {

    private static final class CountingStore implements ConversationStore {
        private final InMemoryConversationStore delegate = new InMemoryConversationStore();
        int loads = 0;

        @Override
        public ConversationSnapshot loadSnapshot(ConversationKey key) {
            loads++;
            return delegate.loadSnapshot(key);
        }

        @Override
        public void append(ConversationKey key, List<IContent> messages, long expectedVersion) {
            delegate.append(key, messages, expectedVersion);
        }

        @Override
        public void clear(ConversationKey key) {
            delegate.clear(key);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    @Test
    public void repeatedLoadHitsCache() {
        CountingStore delegate = new CountingStore();
        BoundedCachingConversationStore store = new BoundedCachingConversationStore(delegate, 10, 100);
        ConversationKey key = new ConversationKey("conv-1", "p", "m");
        store.loadSnapshot(key);
        store.loadSnapshot(key);
        assertEquals(1, delegate.loads);
    }

    @Test
    public void appendInvalidatesCachedSnapshot() {
        CountingStore delegate = new CountingStore();
        BoundedCachingConversationStore store = new BoundedCachingConversationStore(delegate, 10, 100);
        ConversationKey key = new ConversationKey("conv-1", "p", "m");
        store.loadSnapshot(key);
        store.append(key, Collections.singletonList(new UserContent("hello")), 0L);
        ConversationSnapshot snapshot = store.loadSnapshot(key);
        assertEquals(1L, snapshot.version());
        assertEquals(1, snapshot.messages().size());
    }

    @Test
    public void cacheEvictsByConversationCapacity() {
        CountingStore delegate = new CountingStore();
        BoundedCachingConversationStore store = new BoundedCachingConversationStore(delegate, 1, 100);
        ConversationKey key1 = new ConversationKey("conv-1", "p", "m");
        ConversationKey key2 = new ConversationKey("conv-2", "p", "m");
        store.loadSnapshot(key1);
        store.loadSnapshot(key2);
        store.loadSnapshot(key1);
        assertEquals(3, delegate.loads);
    }
}
