//package com.clara.memory.api;
//
//import com.clara.kernel.ChatMessage;
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.List;
//
///**
// * 保存会话在特定时刻的快照。
// */
//public final class ConversationSnapshot {
//    private final long version;
//    private final List<ChatMessage> messages;
//
//    public ConversationSnapshot(long version, List<ChatMessage> messages) {
//        if (version < 0L) { throw new IllegalArgumentException("version must not be negative"); }
//        if (messages == null) { throw new IllegalArgumentException("messages must not be null"); }
//        if (messages.contains(null)) { throw new IllegalArgumentException("messages must not contain null"); }
//        this.version = version;
//        this.messages = Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
//    }
//
//    public long getVersion() { return version; }
//    public List<ChatMessage> getMessages() { return messages; }
//}
