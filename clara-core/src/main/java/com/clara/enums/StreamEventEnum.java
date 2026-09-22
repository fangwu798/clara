package com.clara.enums;

import com.clara.kernel.stream.StreamEvent;

/**
 * 对话流事件类型：{@link StreamEvent#getType()} 的取值。
 *
 * <p>位置：内核共享枚举包（{@code com.clara.enums}）；由流式链路（{@code StreamHandle}）构造事件时写入，
 * 调用方按类型分派处理。
 *
 * <p>取值与使用场景：
 * <ul>
 *   <li>{@code TEXT_DELTA} / {@code REASONING_DELTA} / {@code TOOL_ARGUMENT_DELTA} 是增量取值，只在流
 *       进行中出现，用于即时展示；</li>
 *   <li>{@code TOOL_CALL_COMPLETE} 表示某个工具调用的参数已拼装完整；</li>
 *   <li>{@code LOG} 是观测用的旁路事件（请求过程日志），非终态、可出现在任意位置，接收方可忽略；</li>
 *   <li>{@code RESPONSE_COMPLETE} 与 {@code ERROR} 是终态取值，一次流中只出现其中一个，且只出现一次。</li>
 * </ul>
 *
 * <p>枚举不可变、天然线程安全，可直接用 {@code ==} 比较，无需释放。
 */
public enum StreamEventEnum {
    /**
     * 正文文本的增量片段，片段内容放在 {@link StreamEvent#getPayload()}。
     *
     * <p>两种协议来源：Chat Completions 为 {@code choices[0].delta.content}；Responses 为
     * {@code response.output_text.delta} 事件的 {@code delta}。
     */
    TEXT_DELTA,
    /**
     * 推理内容的增量片段，片段内容放在 {@link StreamEvent#getPayload()}。
     *
     * <p>两种协议来源：Chat Completions 为 {@code choices[0].delta.reasoning_content}；Responses 为
     * {@code response.reasoning_text.delta} 事件的 {@code delta}。
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
     * 请求过程日志事件：观测用的旁路事件，负载为一条日志文本，可出现在流的任意位置，包括终态之后。
     *
     * <p>接收方不关心日志时按类型过滤忽略即可，它不影响 {@code RESPONSE_COMPLETE} / {@code ERROR} 的
     * 终态语义。
     */
    LOG,
    /**
     * 终态事件：本轮流式响应正常结束，累积完成的响应放在 {@link StreamEvent#getResponse()}；
     * 纯工具调用轮可能正文为空，此时 {@link StreamEvent#getPayload()} 为结束原因（如 {@code TOOL_CALLS}）。
     */
    RESPONSE_COMPLETE,
    /** 终态事件：流式过程失败，错误信息放在 {@link StreamEvent#getError()}，失败后不再产生任何事件。 */
    ERROR
}
