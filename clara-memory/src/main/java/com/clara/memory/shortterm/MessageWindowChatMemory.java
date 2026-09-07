package com.clara.memory.shortterm;

import com.clara.memory.api.ChatMemoryStore;
import com.clara.model.api.ChatMessage;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 表示。
 */
public final class MessageWindowChatMemory extends AbstractWindowChatMemory {
    private final String id;
    private final ToIntFunction<String> maxMessagesProvider;

    private MessageWindowChatMemory(Builder builder) {
        super(builder.id, builder.store == null ? new InMemoryChatMemoryStore() : builder.store,
                builder.alwaysKeepSystemFirst);
        if (builder.maxMessagesProvider == null) {
            throw new IllegalArgumentException("maxMessagesProvider must not be null");
        }
        this.id = builder.id;
        this.maxMessagesProvider = builder.maxMessagesProvider;
        maxMessages();
    }

    @Override
    void trim(List<ChatMessage> messages) {
        int maximum = maxMessages();
        while (messages.size() > maximum) {
            int index = WindowSupport.evictionIndex(messages);
            if (index >= messages.size()) return;
            WindowSupport.evictAt(messages, index);
        }
    }

    private int maxMessages() {
        int value = maxMessagesProvider.applyAsInt(id);
        if (value <= 0) { throw new IllegalArgumentException("maxMessages must be positive"); }
        return value;
    }

    public static MessageWindowChatMemory withMaxMessages(int maxMessages) {
        return builder().maxMessages(maxMessages).build();
    }

    public static Builder builder() { return new Builder(); }

    /**
     * 表示。
     */
    public static final class Builder {
        private String id = "default";
        private ToIntFunction<String> maxMessagesProvider;
        private ChatMemoryStore store;
        private boolean alwaysKeepSystemFirst;

        public Builder id(String id) { this.id = id; return this; }
        public Builder maxMessages(final int maxMessages) {
            this.maxMessagesProvider = new ToIntFunction<String>() {
                public int applyAsInt(String ignored) { return maxMessages; }
            };
            return this;
        }
        public Builder dynamicMaxMessages(ToIntFunction<String> provider) {
            this.maxMessagesProvider = provider;
            return this;
        }
        public Builder chatMemoryStore(ChatMemoryStore store) { this.store = store; return this; }
        public Builder alwaysKeepSystemMessageFirst(boolean value) {
            this.alwaysKeepSystemFirst = value;
            return this;
        }
        public MessageWindowChatMemory build() { return new MessageWindowChatMemory(this); }
    }
}
