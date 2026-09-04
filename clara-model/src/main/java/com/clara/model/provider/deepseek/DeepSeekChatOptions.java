package com.clara.model.provider.deepseek;

import com.clara.model.api.ModelProvider;
import com.clara.model.api.ProviderOptions;

public final class DeepSeekChatOptions implements ProviderOptions {
    private final Boolean prefixCompletion;
    private final String reasoningContent;

    public DeepSeekChatOptions(Boolean prefixCompletion, String reasoningContent) {
        if (reasoningContent != null && reasoningContent.trim().isEmpty())
            throw new IllegalArgumentException("reasoningContent must not be blank");
        if (reasoningContent != null && !Boolean.TRUE.equals(prefixCompletion))
            throw new IllegalArgumentException("reasoningContent requires prefix completion");
        this.prefixCompletion = prefixCompletion;
        this.reasoningContent = reasoningContent;
    }

    public ModelProvider getProvider() { return ModelProvider.DEEPSEEK; }
    public Boolean getPrefixCompletion() { return prefixCompletion; }
    public String getReasoningContent() { return reasoningContent; }
}
