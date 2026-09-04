package com.clara.model.provider.anthropic;

import com.clara.model.api.ChatResponse;
import com.clara.model.api.ToolCallContent;

final class AnthropicStreamChunk {
    private final String textDelta;
    private final String toolCallId;
    private final String toolName;
    private final String toolArgumentsDelta;
    private final ToolCallContent completedTool;
    private final ChatResponse response;

    AnthropicStreamChunk(String textDelta, String toolCallId, String toolName,
            String toolArgumentsDelta, ToolCallContent completedTool, ChatResponse response) {
        this.textDelta = textDelta;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolArgumentsDelta = toolArgumentsDelta;
        this.completedTool = completedTool;
        this.response = response;
    }

    String getTextDelta() { return textDelta; }
    String getToolCallId() { return toolCallId; }
    String getToolName() { return toolName; }
    String getToolArgumentsDelta() { return toolArgumentsDelta; }
    ToolCallContent getCompletedTool() { return completedTool; }
    ChatResponse getResponse() { return response; }
    boolean isComplete() { return response != null; }

    static AnthropicStreamChunk empty() {
        return new AnthropicStreamChunk(null, null, null, null, null, null);
    }
}
