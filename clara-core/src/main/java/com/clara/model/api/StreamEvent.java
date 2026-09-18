package com.clara.model.api;

import com.clara.kernel.ClaraError;
import lombok.Data;

/**
 * 表示流产生的事件。
 */
@Data
public final class StreamEvent {
    /**
     * 定义类型的可选取值。
     */
    public enum Type {
        TEXT_DELTA,
        REASONING_DELTA,
        TOOL_ARGUMENT_DELTA,
        TOOL_CALL_COMPLETE,
        RESPONSE_COMPLETE,
        ERROR
    }

    private final Type type;
    private final long sequence;
    private final String payload;
    private final String toolCallId;
    private final String toolName;
    private final ChatResponse response;
    private final ClaraError error;

    public StreamEvent(Type type, long sequence, String payload) {
        this(type, sequence, payload, null, null, null, null);
    }

    public StreamEvent(Type type, long sequence, String payload, String toolCallId, String toolName, ChatResponse response, ClaraError error) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        this.type = type;
        this.sequence = sequence;
        this.payload = payload;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.response = response;
        this.error = error;
    }
}
