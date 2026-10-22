package com.clara.memory.persistence;

import com.clara.core.message.IContent;
import com.clara.enums.RoleEnum;
import com.clara.enums.interaction.HistoryClearScopeEnum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内存会话存储：用并发映射保存会话快照，无容量上限、无持久化、进程重启即丢失。
 *
 * <p>用于测试或短生命周期任务；同样校验 {@code expectedVersion} 保证乐观并发。
 */
public final class InMemoryConversationStore implements ConversationStore {

    private final ConcurrentHashMap<String, ConversationSnapshot> snapshots =
            new ConcurrentHashMap<>();

    /** 会话首次写入的先后序号，用于按时间顺序清理历史。 */
    private final ConcurrentHashMap<String, Long> sequenceByKey = new ConcurrentHashMap<>();

    /** 单调递增的写入序号。 */
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public ConversationSnapshot loadSnapshot(ConversationKey key) {
        requireKey(key);
        ConversationSnapshot snapshot = snapshots.get(key.value());
        if (snapshot == null) {
            return new ConversationSnapshot(0L, Collections.emptyList());
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
        sequenceByKey.computeIfAbsent(key.value(), ignored -> sequence.incrementAndGet());
        snapshots.compute(key.value(), (ignored, current) -> {
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
        });
    }

    @Override
    public void clear(ConversationKey key) {
        requireKey(key);
        snapshots.remove(key.value());
        sequenceByKey.remove(key.value());
    }

    /**
     * 清理全部会话中的聊天记录，口径与 {@code /history} 展示一致（仅用户 / 助手且正文非空）。
     *
     * @param scope 清理范围，不可为 null
     * @param count 清理条数；{@code ALL} 时忽略，其余范围必须为正
     * @return 实际清理的聊天消息条数
     */
    @Override
    public synchronized int deleteChatMessages(HistoryClearScopeEnum scope, int count) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (scope != HistoryClearScopeEnum.ALL && count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        List<String> keys = new ArrayList<>(snapshots.keySet());
        keys.sort(Comparator.comparingLong(key -> sequenceByKey.getOrDefault(key, 0L)));
        int total = 0;
        for (String key : keys) {
            total += chatCount(snapshots.get(key).messages());
        }
        if (scope != HistoryClearScopeEnum.ALL && total == 0) {
            return 0;
        }
        if (scope == HistoryClearScopeEnum.ALL || count >= total) {
            snapshots.clear();
            sequenceByKey.clear();
            return total;
        }
        int left = count;
        if (scope == HistoryClearScopeEnum.FIRST) {
            for (String key : keys) {
                left = removeFirst(key, left);
            }
        } else {
            for (int index = keys.size() - 1; index >= 0; index--) {
                left = removeLast(keys.get(index), left);
            }
        }
        return count - left;
    }

    @Override
    public void close() {
        snapshots.clear();
        sequenceByKey.clear();
    }

    private int removeFirst(String key, int count) {
        ConversationSnapshot snapshot = snapshots.get(key);
        if (snapshot == null || count <= 0) {
            return count;
        }
        List<IContent> kept = new ArrayList<>(snapshot.messages().size());
        int left = count;
        for (IContent message : snapshot.messages()) {
            if (left > 0 && isChatMessage(message)) {
                left--;
            } else {
                kept.add(message);
            }
        }
        replace(key, snapshot, kept);
        return left;
    }

    private int removeLast(String key, int count) {
        ConversationSnapshot snapshot = snapshots.get(key);
        if (snapshot == null || count <= 0) {
            return count;
        }
        boolean[] removed = new boolean[snapshot.messages().size()];
        int left = count;
        for (int index = snapshot.messages().size() - 1; index >= 0 && left > 0; index--) {
            if (isChatMessage(snapshot.messages().get(index))) {
                removed[index] = true;
                left--;
            }
        }
        List<IContent> kept = new ArrayList<>(snapshot.messages().size());
        for (int index = 0; index < snapshot.messages().size(); index++) {
            if (!removed[index]) {
                kept.add(snapshot.messages().get(index));
            }
        }
        replace(key, snapshot, kept);
        return left;
    }

    private void replace(String key, ConversationSnapshot current, List<IContent> kept) {
        if (kept.size() == current.messages().size()) {
            return;
        }
        if (!hasChatMessage(kept)) {
            snapshots.remove(key);
            sequenceByKey.remove(key);
            return;
        }
        snapshots.put(key, new ConversationSnapshot(current.version() + 1L, kept));
    }

    private static int chatCount(List<IContent> messages) {
        int count = 0;
        for (IContent message : messages) {
            if (isChatMessage(message)) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasChatMessage(List<IContent> messages) {
        for (IContent message : messages) {
            if (isChatMessage(message)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isChatMessage(IContent message) {
        if (message == null) {
            return false;
        }
        RoleEnum role = message.getRoleType();
        if (role != RoleEnum.USER && role != RoleEnum.ASSISTANT) {
            return false;
        }
        Object content = message.toCompletionMessage().get("content");
        if (content instanceof String) {
            return !((String) content).trim().isEmpty();
        }
        if (content instanceof List) {
            for (Object part : (List<?>) content) {
                if (part instanceof Map) {
                    Object text = ((Map<?, ?>) part).get("text");
                    if (text instanceof String && !((String) text).trim().isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void requireKey(ConversationKey key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
    }
}
