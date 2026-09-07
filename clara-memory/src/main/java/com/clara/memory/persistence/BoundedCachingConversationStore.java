package com.clara.memory.persistence;

import com.clara.memory.api.ConversationSnapshot;
import com.clara.memory.api.ConversationStore;
import com.clara.model.api.ChatMessage;
import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 为会话存储增加按需加载且容量受限的进程内 LRU 缓存。
 *
 * <p>常规用法：使用 {@code LocalConversationStore} 或 {@code JdbcConversationStore} 作为下层持久化存储，
 * 再以最大缓存会话数和最大缓存消息数构造本类。创建实例时不会预加载历史会话；首次读取某个会话时才访问
 * 下层存储，后续读取在缓存有效时直接返回。追加或清空会话会先写入下层存储，成功后使对应缓存失效。
 *
 * <p>注意：两个容量限制约束的是长期驻留在缓存中的数据。单个会话超过消息数上限时仍会返回给当前调用方，
 * 但不会进入缓存。下层存储不得绕过本类被并发修改，否则已缓存的快照可能过期。本类关闭时会清空缓存，
 * 并关闭实现了 {@link Closeable} 的下层存储，因此下层存储的生命周期应由本实例独占管理。
 */
public final class BoundedCachingConversationStore implements ConversationStore, Closeable {
    private final ConversationStore delegate;
    private final int maxCachedConversations;
    private final long maxCachedMessages;
    private final LinkedHashMap<String, ConversationSnapshot> cache =
            new LinkedHashMap<String, ConversationSnapshot>(16, 0.75f, true);
    private long cachedMessageCount;
    private boolean closed;

    public BoundedCachingConversationStore(ConversationStore delegate,
            int maxCachedConversations, long maxCachedMessages) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (maxCachedConversations <= 0) {
            throw new IllegalArgumentException("maxCachedConversations must be positive");
        }
        if (maxCachedMessages <= 0L) {
            throw new IllegalArgumentException("maxCachedMessages must be positive");
        }
        this.delegate = delegate;
        this.maxCachedConversations = maxCachedConversations;
        this.maxCachedMessages = maxCachedMessages;
    }

    @Override
    public synchronized List<ChatMessage> load(String conversationId) {
        return loadSnapshot(conversationId).getMessages();
    }

    @Override
    public synchronized ConversationSnapshot loadSnapshot(String conversationId) {
        ensureOpen();
        ConversationSnapshot cached = cache.get(conversationId);
        if (cached != null) {
            return copy(cached);
        }
        ConversationSnapshot loaded = copy(delegate.loadSnapshot(conversationId));
        cache(conversationId, loaded);
        return copy(loaded);
    }

    @Override
    public synchronized void append(String conversationId, List<ChatMessage> messages,
            long expectedVersion) {
        ensureOpen();
        delegate.append(conversationId, messages, expectedVersion);
        remove(conversationId);
    }

    @Override
    public synchronized void clear(String conversationId) {
        ensureOpen();
        delegate.clear(conversationId);
        remove(conversationId);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        cache.clear();
        cachedMessageCount = 0L;
        if (delegate instanceof Closeable) {
            ((Closeable) delegate).close();
        }
    }

    private void cache(String conversationId, ConversationSnapshot snapshot) {
        long messageCount = snapshot.getMessages().size();
        if (messageCount > maxCachedMessages) {
            return;
        }
        ConversationSnapshot previous = cache.put(conversationId, copy(snapshot));
        if (previous != null) {
            cachedMessageCount -= previous.getMessages().size();
        }
        cachedMessageCount += messageCount;
        while (cache.size() > maxCachedConversations
                || cachedMessageCount > maxCachedMessages) {
            Map.Entry<String, ConversationSnapshot> eldest = cache.entrySet().iterator().next();
            cachedMessageCount -= eldest.getValue().getMessages().size();
            cache.remove(eldest.getKey());
        }
    }

    private void remove(String conversationId) {
        ConversationSnapshot removed = cache.remove(conversationId);
        if (removed != null) {
            cachedMessageCount -= removed.getMessages().size();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("conversation store is closed");
        }
    }

    private static ConversationSnapshot copy(ConversationSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalStateException("delegate returned null snapshot");
        }
        return new ConversationSnapshot(snapshot.getVersion(), snapshot.getMessages());
    }
}
