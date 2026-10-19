package com.clara.memory.persistence;

import com.clara.core.message.IContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内存会话存储：用并发映射保存会话快照，无容量上限、无持久化、进程重启即丢失。
 *
 * <p>用于测试或短生命周期任务；同样校验 {@code expectedVersion} 保证乐观并发。
 */
public final class InMemoryConversationStore implements ConversationStore {

    private final ConcurrentHashMap<String, ConversationSnapshot> snapshots =
            new ConcurrentHashMap<>();

    @Override
    public ConversationSnapshot loadSnapshot(ConversationKey key) {
        requireKey(key);
        ConversationSnapshot snapshot = snapshots.get(key.value());
        if (snapshot == null) {
            return new ConversationSnapshot(0L, Collections.<IContent>emptyList());
        }
        return snapshot;
    }

    @Override
    public void append(final ConversationKey key, final List<IContent> messages,
            final long expectedVersion) {
        requireKey(key);
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        snapshots.compute(key.value(), new java.util.function.BiFunction<String, ConversationSnapshot, ConversationSnapshot>() {
            @Override
            public ConversationSnapshot apply(String ignored, ConversationSnapshot current) {
                long currentVersion = current == null ? 0L : current.version();
                if (currentVersion != expectedVersion) {
                    throw ConversationStoreErrors.versionConflict();
                }
                List<IContent> merged = new ArrayList<>();
                if (current != null) {
                    merged.addAll(current.messages());
                }
                for (IContent message : messages) {
                    if (message == null) {
                        throw new IllegalArgumentException("messages must not contain null");
                    }
                    merged.add(message);
                }
                return new ConversationSnapshot(expectedVersion + 1L, merged);
            }
        });
    }

    @Override
    public void clear(ConversationKey key) {
        requireKey(key);
        snapshots.remove(key.value());
    }

    @Override
    public void close() {
        snapshots.clear();
    }

    private static void requireKey(ConversationKey key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
    }
}
