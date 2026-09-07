package com.clara.memory.shortterm;

import com.clara.memory.api.ChatMemoryStore;
import com.clara.model.api.ChatMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供对话记忆存储器的内存实现。
 */
public final class InMemoryChatMemoryStore implements ChatMemoryStore {
    private final Map<String, List<ChatMessage>> messagesById =
            new ConcurrentHashMap<String, List<ChatMessage>>();

    @Override
    public List<ChatMessage> getMessages(String memoryId) {
        requireId(memoryId);
        List<ChatMessage> messages = messagesById.get(memoryId);
        return messages == null
                ? Collections.<ChatMessage>emptyList()
                : new ArrayList<ChatMessage>(messages);
    }

    @Override
    public void updateMessages(String memoryId, List<ChatMessage> messages) {
        requireId(memoryId);
        if (messages == null) { throw new IllegalArgumentException("messages must not be null"); }
        messagesById.put(memoryId, Collections.unmodifiableList(new ArrayList<ChatMessage>(messages)));
    }

    @Override
    public void deleteMessages(String memoryId) {
        requireId(memoryId);
        messagesById.remove(memoryId);
    }

    private static void requireId(String memoryId) {
        if (memoryId == null || memoryId.trim().isEmpty()) {
            throw new IllegalArgumentException("memoryId must not be blank");
        }
    }
}
