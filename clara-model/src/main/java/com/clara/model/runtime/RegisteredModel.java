package com.clara.model.runtime;

import com.clara.model.api.ChatModel;
import com.clara.model.api.EmbeddingModel;
import com.clara.model.api.StreamingChatModel;

/**
 * 提供已注册模型能力。
 */
public final class RegisteredModel {
    private final ProviderModelDescriptor descriptor;
    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final EmbeddingModel embeddingModel;
    public RegisteredModel(ProviderModelDescriptor descriptor, ChatModel chatModel,
            StreamingChatModel streamingChatModel, EmbeddingModel embeddingModel) {
        if (descriptor == null) { throw new IllegalArgumentException("descriptor must not be null"); }
        if (descriptor.supports(com.clara.model.api.ModelCapability.CHAT_TEXT)
                && chatModel == null && streamingChatModel == null) {
            throw new IllegalArgumentException("chat capability requires a chat endpoint");
        }
        if (descriptor.supports(com.clara.model.api.ModelCapability.EMBEDDING)
                && embeddingModel == null) {
            throw new IllegalArgumentException("embedding capability requires an embedding endpoint");
        }
        this.descriptor = descriptor;
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
    }
    public ProviderModelDescriptor getDescriptor() { return descriptor; }
    public ChatModel getChatModel() { return chatModel; }
    public StreamingChatModel getStreamingChatModel() { return streamingChatModel; }
    public EmbeddingModel getEmbeddingModel() { return embeddingModel; }
}
