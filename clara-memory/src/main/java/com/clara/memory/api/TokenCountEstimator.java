package com.clara.memory.api;

import com.clara.model.api.ChatMessage;

public interface TokenCountEstimator {
    int estimateTokenCountInMessage(ChatMessage message);

    int estimateTokenCountInMessages(Iterable<ChatMessage> messages);
}
