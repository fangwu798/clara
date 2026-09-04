package com.clara.model.api;

public interface StreamingChatModel {
    StreamHandle chat(ChatRequest request, StreamObserver observer);
}
