package com.clara.memory.shortterm;

import com.clara.memory.api.ChatMemory;
import com.clara.memory.api.ChatMemoryStore;
import com.clara.model.api.ChatMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 为消息数窗口和 Token 窗口提供线程安全的对话记忆读写、系统消息归一化及裁剪框架。
 *
 * <p>常规用法：使用具体的 {@link MessageWindowChatMemory} 或 {@link TokenWindowChatMemory}，通过
 * {@link #add} 追加消息、{@link #messages} 获取不可变快照，替换全部消息使用 {@link #set}。
 *
 * <p>注意：并发互斥建立在共享的 {@link ChatMemoryStore} 实例上；相同会话 ID 若使用不同存储实例，
 * 无法获得原子性。裁剪会尽量保留系统消息，并按完整工具调用消息组淘汰，避免产生不完整上下文。
 */
abstract class AbstractWindowChatMemory implements ChatMemory {
    private final String id;
    private final ChatMemoryStore store;
    private final boolean alwaysKeepSystemFirst;

    AbstractWindowChatMemory(String id, ChatMemoryStore store, boolean alwaysKeepSystemFirst) {
        if (id == null || id.trim().isEmpty()) { throw new IllegalArgumentException("id must not be blank"); }
        if (store == null) { throw new IllegalArgumentException("store must not be null"); }
        this.id = id;
        this.store = store;
        this.alwaysKeepSystemFirst = alwaysKeepSystemFirst;
    }

    public final String getId() { return id; }

    @Override
    public final void add(ChatMessage message) {
        if (message == null) { throw new IllegalArgumentException("message must not be null"); }
        synchronized (store) {
            List<ChatMessage> messages = new ArrayList<ChatMessage>(store.getMessages(id));
            if (!WindowSupport.addOrReplaceSystem(messages, message, alwaysKeepSystemFirst)) return;
            trim(messages);
            store.updateMessages(id, messages);
        }
    }

    @Override
    public final void set(List<ChatMessage> source) {
        if (source == null) { throw new IllegalArgumentException("messages must not be null"); }
        synchronized (store) {
            List<ChatMessage> messages = new ArrayList<ChatMessage>(source);
            WindowSupport.normalizeSystems(messages, alwaysKeepSystemFirst);
            trim(messages);
            store.updateMessages(id, messages);
        }
    }

    @Override
    public final List<ChatMessage> messages() {
        synchronized (store) {
            List<ChatMessage> messages = new ArrayList<ChatMessage>(store.getMessages(id));
            int originalSize = messages.size();
            trim(messages);
            if (messages.size() != originalSize) store.updateMessages(id, messages);
            return Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
        }
    }

    @Override
    public final void clear() {
        synchronized (store) {
            store.deleteMessages(id);
        }
    }

    abstract void trim(List<ChatMessage> messages);
}
