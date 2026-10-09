package com.clara.memory.persistence;

import com.clara.core.message.IContent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 有界缓存会话存储：包装底层存储，按 LRU 缓存会话快照并限制常驻内存。
 *
 * <p>两个容量限制：最大缓存会话数与最大缓存消息数。首次读取某会话才访问底层，后续读取命中缓存直接返回；
 * {@code append} / {@code clear} 先写底层、成功后使对应缓存失效。单会话消息数超过缓存上限时仍返回给当前
 * 调用方，但不进入缓存。
 */
public final class BoundedCachingConversationStore implements ConversationStore {

    private final ConversationStore delegate;
    private final int maxCachedConversations;
    private final int maxCachedMessages;
    private final Map<String, ConversationSnapshot> cache;

    /**
     * 创建有界缓存包装。
     *
     * @param delegate              底层存储，不可为 null
     * @param maxCachedConversations 最大缓存会话数，必须为正
     * @param maxCachedMessages      单会话最大缓存消息数，必须为正
     * @throws IllegalArgumentException 任一参数不合法时
     */
    public BoundedCachingConversationStore(ConversationStore delegate,
            int maxCachedConversations, int maxCachedMessages) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (maxCachedConversations <= 0 || maxCachedMessages <= 0) {
            throw new IllegalArgumentException("cache limits must be positive");
        }
        this.delegate = delegate;
        this.maxCachedConversations = maxCachedConversations;
        this.maxCachedMessages = maxCachedMessages;
        this.cache = new LinkedHashMap<String, ConversationSnapshot>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ConversationSnapshot> eldest) {
                return size() > maxCachedConversations;
            }
        };
    }

    @Override
    public synchronized ConversationSnapshot loadSnapshot(ConversationKey key) {
        requireKey(key);
        String value = key.value();
        ConversationSnapshot cached = cache.get(value);
        if (cached != null) {
            return cached;
        }
        ConversationSnapshot loaded = delegate.loadSnapshot(key);
        if (loaded != null && loaded.messages().size() <= maxCachedMessages) {
            cache.put(value, loaded);
        }
        return loaded;
    }

    @Override
    public synchronized void append(ConversationKey key, List<IContent> messages, long expectedVersion) {
        requireKey(key);
        delegate.append(key, messages, expectedVersion);
        cache.remove(key.value());
    }

    @Override
    public synchronized void clear(ConversationKey key) {
        requireKey(key);
        delegate.clear(key);
        cache.remove(key.value());
    }

    @Override
    public synchronized void close() {
        cache.clear();
        delegate.close();
    }

    private static void requireKey(ConversationKey key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
    }
}
