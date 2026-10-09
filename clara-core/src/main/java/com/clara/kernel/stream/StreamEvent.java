package com.clara.kernel.stream;

import com.clara.enums.StreamEventEnum;
import com.clara.kernel.api.IResponse;
import com.clara.exception.ClaraError;
import lombok.Data;

/**
 * 一次调用的流事件。
 *
 * <p>位置：契约层（{@code com.clara.kernel.stream}）。由流式链路（{@code StreamHandle}）构造并回调给调用方，
 * 调用方据 {@link #getType()} 分派处理。
 *
 * <p>使用方法：
 * <ol>
 *   <li>事件由流式链路构造，调用方不自行创建；</li>
 *   <li>增量事件（{@code TEXT_DELTA} / {@code REASONING_DELTA} / {@code TOOL_ARGUMENT_DELTA}）用于即时展示；</li>
 *   <li>{@link StreamEventEnum#RESPONSE_COMPLETE} 与 {@link StreamEventEnum#ERROR} 是终态，一次流中只出现
 *       其中一个且只出现一次；前者通过 {@link #getResponse()} 携带累积完成的响应对象；</li>
 *   <li>本类不可变，可跨线程传递，无需释放资源。</li>
 * </ol>
 *
 * <p>失败语义：构造时类型为 null 或序号为负抛 {@link IllegalArgumentException}；运行期错误以
 * {@link StreamEventEnum#ERROR} 事件（携带 {@link #getError()}）表达，而非抛出。
 */
@Data
public final class StreamEvent {
    /**
     * 事件类型，非 null，决定其余字段的有效性。
     */
    private final StreamEventEnum type;
    /**
     * 事件序号，从 0 开始单调递增，非负；调用方据此保证事件顺序。
     */
    private final long sequence;
    /**
     * 事件负载：增量事件为片段文本（对象 / 数组类增量为 JSON 文本），终态事件可为结束原因；无负载时为 null。
     */
    private final String payload;
    /**
     * 工具调用 ID；仅工具相关事件携带时非 null。
     */
    private final String toolCallId;
    /**
     * 工具名；仅工具相关事件携带时非 null。
     */
    private final String toolName;
    /**
     * 累积完成的响应对象；仅 {@link StreamEventEnum#RESPONSE_COMPLETE} 事件非 null。
     */
    private final IResponse response;
    /**
     * 错误信息；仅 {@link StreamEventEnum#ERROR} 事件非 null。
     */
    private final ClaraError error;

    /**
     * 创建仅含类型、序号与负载的事件（工具、终态与错误字段均为 null）。
     *
     * @param type     事件类型，不可为 null
     * @param sequence 事件序号，不得为负
     * @param payload  事件负载，可为 null
     * @throws IllegalArgumentException 类型为 null 或序号为负时
     */
    public StreamEvent(StreamEventEnum type, long sequence, String payload) {
        this(type, sequence, payload, null, null, null, null);
    }

    /**
     * 创建完整事件。
     *
     * @param type       事件类型，不可为 null
     * @param sequence   事件序号，不得为负
     * @param payload    事件负载，可为 null
     * @param toolCallId 工具调用 ID，可为 null
     * @param toolName   工具名，可为 null
     * @param response   累积完成的响应对象，仅终态完成事件非 null
     * @param error      错误信息，仅错误事件非 null
     * @throws IllegalArgumentException 类型为 null 或序号为负时
     */
    public StreamEvent(StreamEventEnum type, long sequence, String payload, String toolCallId, String toolName, IResponse response, ClaraError error) {
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
