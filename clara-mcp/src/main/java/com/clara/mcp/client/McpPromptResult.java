package com.clara.mcp.client;

import com.clara.model.api.ChatMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 封装MCP提示词操作的执行结果。
 */
public final class McpPromptResult {
    private final String description;
    private final List<ChatMessage> messages;
    public McpPromptResult(String description, List<ChatMessage> messages) {
        if (messages == null || messages.contains(null)) {
            throw new IllegalArgumentException("messages must not be null or contain null");
        }
        this.description = description;
        this.messages = Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
    }
    public String getDescription() { return description; }
    public List<ChatMessage> getMessages() { return messages; }
}
