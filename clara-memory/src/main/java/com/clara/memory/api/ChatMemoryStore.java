package com.clara.memory.api;

import com.clara.model.api.ChatMessage;
import java.util.List;

public interface ChatMemoryStore {
    List<ChatMessage> getMessages(String memoryId);
    void updateMessages(String memoryId, List<ChatMessage> messages);
    void deleteMessages(String memoryId);
}
