package com.clara.model.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ChatResponse {
    private final ChatMessage message;
    private final TokenUsage tokenUsage;
    private final FinishReason finishReason;
    private final Map<String, String> providerMetadata;

    public ChatResponse(ChatMessage message, TokenUsage tokenUsage,
            FinishReason finishReason, Map<String, String> providerMetadata) {
        if (message == null) throw new IllegalArgumentException("message must not be null");
        if (finishReason == null) throw new IllegalArgumentException("finishReason must not be null");
        this.message = message;
        this.tokenUsage = tokenUsage;
        this.finishReason = finishReason;
        this.providerMetadata = providerMetadata == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(providerMetadata));
    }
    public ChatMessage getMessage() { return message; }
    public TokenUsage getTokenUsage() { return tokenUsage; }
    public FinishReason getFinishReason() { return finishReason; }
    public Map<String, String> getProviderMetadata() { return providerMetadata; }
}
