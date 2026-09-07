package com.clara.memory.api;

import com.clara.model.api.ChatMessage;
import java.util.List;

/**
 * 定义。
 */
public interface ChatMemory {
    String getId();
    void add(ChatMessage message);
    void set(List<ChatMessage> messages);
    List<ChatMessage> messages();
    void clear();
}
