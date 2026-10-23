package com.clara.enums;

/**
 * 调用协议档位：同一平台同一能力下，请求体 / 响应体线格式可能因协议而异。
 *
 * <p>是什么、在哪一层：内核共享枚举包（{@code com.clara.enums}）中的值类型，与
 * {@link ModelProviderEnum}、{@link CapabilityKindEnum} 平级。它回答「这次调用走哪种 HTTP 协议」，
 * 用于在编解码阶段选择字段名（入参）与取值路径（出参）的协议专属规则。
 *
 * <p>为什么需要它：能力节点上的 {@code apiProfile} 是自由字符串，编解码器无法直接据此分派；把协议收敛为
 * 有限枚举后，字段上的协议注解（{@code @ProtocolField} / {@code @ProtocolResponse}）才能用枚举值声明
 * 「该字段在哪种协议下叫什么名字 / 从哪里取值」。
 *
 * <p>取值：
 * <ul>
 *   <li>{@link #COMPLETION}：OpenAI Chat Completions 兼容协议（{@code /v1/chat/completions}），
 *       也是 DeepSeek、火山方舟、阿里通义等兼容家族的默认线格式；</li>
 *   <li>{@link #RESPONSE}：OpenAI Responses 协议（{@code /v1/responses}），请求体用 {@code input} /
 *       {@code max_output_tokens}，响应体用 {@code output[]} / {@code usage.input_tokens} 等。</li>
 *   <li>{@link #MESSAGES}：Anthropic Messages 协议（{@code /v1/messages}），请求体用 {@code messages} /
 *       {@code system} / {@code max_tokens}（必填） / {@code input_schema} 工具声明，响应体用
 *       {@code content[]}（{@code text} / {@code thinking} / {@code tool_use} 块）与 {@code stop_reason}。</li>
 * </ul>
 */
public enum ProtocolEnum {
    /** OpenAI Chat Completions 兼容协议，默认档位。 */
    COMPLETION,
    /** OpenAI Responses 协议。 */
    RESPONSE,
    /** Anthropic Messages 协议（Claude）。 */
    MESSAGES
}
