package com.clara.memory.api;

import com.clara.model.api.ChatMessage;

/**
 * 估算令牌计数。
 */
public interface TokenCountEstimator {
    int estimateTokenCountInMessage(ChatMessage message);

    int estimateTokenCountInMessages(Iterable<ChatMessage> messages);
}
