package com.clara.enums;

import com.clara.kernel.stream.StreamEvent;

/**
 * 对话流事件类型。
 *
 * <p>位置：契约层（{@code com.clara.model.api}）；原为 {@code StreamEvent} 的内部枚举，现提升为顶层枚举，
 * 供 {@link StreamEvent#getType()} 的调用方按类型分派处理。
 *
 * <p>取值与使用场景：
 * <ul>
 *   <li>前三个增量取值（{@code TEXT_DELTA} / {@code REASONING_DELTA} / {@code TOOL_ARGUMENT_DELTA}）只在流
 *       进行中出现，用于即时展示；</li>
 *   <li>{@code TOOL_CALL_COMPLETE} 用于在流内先执行工具调用；</li>
 *   <li>{@code LOG} 是观测用的旁路事件（请求过程日志），非终态、可出现在任意位置，接收方可忽略；</li>
 *   <li>{@code RESPONSE_COMPLETE} 与 {@code ERROR} 是终态取值，一次流中只出现其中一个，且只出现一次。</li>
 * </ul>
 *
 * <p>枚举不可变、天然线程安全，可直接用 {@code ==} 比较，无需释放。
 */
public enum StreamEventEnum {
    /** 正文文本的增量片段，片段内容放在 {@link StreamEvent#getPayload()}。 */
    TEXT_DELTA,
    /** 推理内容的增量片段（如 DeepSeek 返回的 {@code reasoning_content}），片段内容放在 {@link StreamEvent#getPayload()}。 */
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
     * 请求过程日志事件：负载为一条  可出现在流的任意位置，包括终态之后。
     *
     * <p>只有配置开启日志档位且调用方在 {@code CallContext} 上设置了日志观察者时才会产生；接收方不关心
     * 日志时按类型过滤忽略即可，不影响 {@code RESPONSE_COMPLETE} / {@code ERROR} 的终态语义。
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
