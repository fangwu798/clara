package com.clara.memory.persistence.local;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.memory.api.ConversationSnapshot;
import com.clara.memory.api.ConversationStore;
import com.clara.model.api.ChatMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供仅在当前进程内保存会话快照的存储实现。
 *
 * <p>常规用法：用于单元测试、短生命周期任务或明确允许进程退出后丢失数据的场景；通过
 * {@link #loadSnapshot} 获取版本，再将该版本传给 {@link #append} 完成乐观并发追加。
 *
 * <p>注意：本类不会写入本地文件，也不会在程序重启后恢复历史会话，并且没有容量上限或淘汰策略。
 * 长期运行及生产环境应使用本地或 JDBC 持久化存储；需要读取加速时，可在持久化实现外包装
 * {@link com.clara.memory.persistence.BoundedCachingConversationStore}，按需加载并限制常驻内存规模。
 */
public final class InMemoryConversationStore implements ConversationStore {
    private final Map<String, ConversationSnapshot> conversations =
            new ConcurrentHashMap<String, ConversationSnapshot>();

    @Override
    public synchronized List<ChatMessage> load(String conversationId) {
        return loadSnapshot(conversationId).getMessages();
    }

    @Override
    public synchronized ConversationSnapshot loadSnapshot(String conversationId) {
        requireId(conversationId);
        ConversationSnapshot snapshot = conversations.get(conversationId);
        return snapshot == null
                ? new ConversationSnapshot(0L, Collections.<ChatMessage>emptyList())
                : new ConversationSnapshot(snapshot.getVersion(), snapshot.getMessages());
    }

    @Override
    public synchronized void append(String conversationId, List<ChatMessage> messages,
            long expectedVersion) {
        requireId(conversationId);
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (messages.contains(null)) { throw new IllegalArgumentException("messages must not contain null"); }
        ConversationSnapshot current = loadSnapshot(conversationId);
        if (current.getVersion() != expectedVersion) { throw versionConflict(conversationId); }
        List<ChatMessage> combined = new ArrayList<ChatMessage>(current.getMessages());
        combined.addAll(messages);
        conversations.put(conversationId, new ConversationSnapshot(expectedVersion + 1L, combined));
    }

    @Override
    public synchronized void clear(String conversationId) {
        requireId(conversationId);
        conversations.remove(conversationId);
    }

    private static ClaraException versionConflict(String conversationId) {
        Map<String, String> details = new LinkedHashMap<String, String>();
        details.put("conversationId", conversationId);
        return new ClaraException(new ClaraError(ErrorCodes.CONVERSATION_VERSION_CONFLICT,
                ErrorCategory.CONFLICT, ErrorCodes.CONVERSATION_VERSION_CONFLICT, false,
                null, null, null, null, details));
    }

    private static void requireId(String conversationId) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
    }
}
