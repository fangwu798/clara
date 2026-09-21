//package com.clara.memory.api;
//
//import com.clara.kernel.ChatMessage;
//import java.util.List;
//
///**
// * 存储并读取会话。
// */
//public interface ConversationStore {
//    List<ChatMessage> load(String conversationId);
//    ConversationSnapshot loadSnapshot(String conversationId);
//    void append(String conversationId, List<ChatMessage> messages, long expectedVersion);
//    void clear(String conversationId);
//}
