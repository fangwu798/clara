package com.clara.memory.shortterm;

import com.clara.memory.api.ChatMemoryStore;
import com.clara.memory.api.TokenCountEstimator;
import com.clara.model.api.ChatMessage;
import java.util.List;
import java.util.function.ToIntFunction;

public final class TokenWindowChatMemory extends AbstractWindowChatMemory {
    private final String id;
    private final ToIntFunction<String> maxTokensProvider;
    private final TokenCountEstimator estimator;

    private TokenWindowChatMemory(Builder builder) {
        super(builder.id, builder.store == null ? new InMemoryChatMemoryStore() : builder.store,
                builder.alwaysKeepSystemFirst);
        if (builder.maxTokensProvider == null) {
            throw new IllegalArgumentException("maxTokensProvider must not be null");
        }
        if (builder.estimator == null) throw new IllegalArgumentException("estimator must not be null");
        this.id = builder.id;
        this.maxTokensProvider = builder.maxTokensProvider;
        this.estimator = builder.estimator;
        maxTokens();
    }

    @Override
    void trim(List<ChatMessage> messages) {
        int maximum = maxTokens();
        while (!messages.isEmpty() && tokenCount(messages) > maximum) {
            int index = WindowSupport.evictionIndex(messages);
            if (index >= messages.size()) return;
            WindowSupport.evictAt(messages, index);
        }
    }

    private int tokenCount(List<ChatMessage> messages) {
        int count = estimator.estimateTokenCountInMessages(messages);
        if (count < 0) throw new IllegalStateException("token count must not be negative");
        return count;
    }

    private int maxTokens() {
        int value = maxTokensProvider.applyAsInt(id);
        if (value <= 0) throw new IllegalArgumentException("maxTokens must be positive");
        return value;
    }

    public static TokenWindowChatMemory withMaxTokens(int maxTokens,
            TokenCountEstimator estimator) {
        return builder().maxTokens(maxTokens, estimator).build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id = "default";
        private ToIntFunction<String> maxTokensProvider;
        private TokenCountEstimator estimator;
        private ChatMemoryStore store;
        private boolean alwaysKeepSystemFirst;

        public Builder id(String id) { this.id = id; return this; }
        public Builder maxTokens(final int maxTokens, TokenCountEstimator estimator) {
            this.maxTokensProvider = new ToIntFunction<String>() {
                public int applyAsInt(String ignored) { return maxTokens; }
            };
            this.estimator = estimator;
            return this;
        }
        public Builder dynamicMaxTokens(ToIntFunction<String> provider,
                TokenCountEstimator estimator) {
            this.maxTokensProvider = provider;
            this.estimator = estimator;
            return this;
        }
        public Builder chatMemoryStore(ChatMemoryStore store) { this.store = store; return this; }
        public Builder alwaysKeepSystemMessageFirst(boolean value) {
            this.alwaysKeepSystemFirst = value;
            return this;
        }
        public TokenWindowChatMemory build() { return new TokenWindowChatMemory(this); }
    }
}
