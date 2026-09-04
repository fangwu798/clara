package com.clara.memory.shortterm;

import com.clara.memory.api.ChatMemory;
import com.clara.memory.api.ChatMemoryStore;
import com.clara.model.api.ChatMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

abstract class AbstractWindowChatMemory implements ChatMemory {
    private final String id;
    private final ChatMemoryStore store;
    private final boolean alwaysKeepSystemFirst;

    AbstractWindowChatMemory(String id, ChatMemoryStore store, boolean alwaysKeepSystemFirst) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("id must not be blank");
        if (store == null) throw new IllegalArgumentException("store must not be null");
        this.id = id;
        this.store = store;
        this.alwaysKeepSystemFirst = alwaysKeepSystemFirst;
    }

    public final String getId() { return id; }

    @Override
    public final void add(ChatMessage message) {
        if (message == null) throw new IllegalArgumentException("message must not be null");
        synchronized (store) {
            List<ChatMessage> messages = new ArrayList<ChatMessage>(store.getMessages(id));
            if (!WindowSupport.addOrReplaceSystem(messages, message, alwaysKeepSystemFirst)) return;
            trim(messages);
            store.updateMessages(id, messages);
        }
    }

    @Override
    public final void set(List<ChatMessage> source) {
        if (source == null) throw new IllegalArgumentException("messages must not be null");
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
