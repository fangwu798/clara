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
     *
     * <p>事件顺序由 {@link StreamEvent#getSequence()} 保证；{@link #RESPONSE_COMPLETE} 与 {@link #ERROR}
     * 是终态事件，一次流中只会发出其中一个，且只发一次。
     */
    public enum Type {
        /**
         * 正文文本的增量片段，片段内容放在 {@link StreamEvent#getPayload()}。
         */
        TEXT_DELTA,
        /**
         * 推理内容的增量片段（如 DeepSeek 返回的 {@code reasoning_content}），片段内容放在
         * {@link StreamEvent#getPayload()}。
         */
        REASONING_DELTA,
        /**
         * 工具调用参数的增量片段，片段内容放在 {@link StreamEvent#getPayload()}；
         * {@link StreamEvent#getToolCallId()}、{@link StreamEvent#getToolName()} 仅在本次事件携带时非 null。
         */
        TOOL_ARGUMENT_DELTA,
        /**
         * 某个工具调用的参数已拼装完整；{@link StreamEvent#getToolCallId()}、{@link StreamEvent#getToolName()}
         * 与完整参数（{@link StreamEvent#getPayload()}）在该事件上一定非 null。
         */
        TOOL_CALL_COMPLETE,
        /**
         * 终态事件：本轮流式响应正常结束，累积完成的响应放在 {@link StreamEvent#getResponse()}；
         * 纯工具调用轮可能正文为空，此时 {@link StreamEvent#getPayload()} 为结束原因（如 {@code TOOL_CALLS}）。
         */
        RESPONSE_COMPLETE,
        /**
         * 终态事件：流式过程失败，错误信息放在 {@link StreamEvent#getError()}，失败后不再产生任何事件。
         */
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
