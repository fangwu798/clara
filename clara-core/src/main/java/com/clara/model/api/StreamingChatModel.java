package com.clara.model.api;

/**
 * 提供流式对话模型能力。
 */
public interface StreamingChatModel {
    StreamHandle chat(ChatRequest request, StreamObserver observer);
}
