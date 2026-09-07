package com.clara.model.api;

/**
 * 提供对话模型能力。
 */
public interface ChatModel {
    ChatResponse chat(ChatRequest request);
}
