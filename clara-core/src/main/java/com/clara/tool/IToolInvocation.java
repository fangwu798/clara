package com.clara.tool;

/**
 * 统一工具调用视图：模型在响应中要求调用的一次工具，屏蔽 Chat Completions / Responses 两种协议的结构差异。
 *
 * <p>是什么、在哪一层：契约层的工具调用抽象（包 {@code com.clara.tool}）。{@link ToolCall}（Chat Completions）
 * 与 {@link ResponsesToolCall}（Responses）都实现本接口，调用方统一通过本接口读取 {@code callId/name/arguments}，
 * 无需感知协议。
 *
 * <p>字段口径：
 * <ul>
 *   <li>{@link #callId()}：工具调用回执 ID（Chat 的 {@code id} / Responses 的 {@code call_id}）；</li>
 *   <li>{@link #name()}：被调用的函数名；</li>
 *   <li>{@link #arguments()}：模型生成的入参 JSON 字符串，可能不是合法 JSON。</li>
 * </ul>
 *
 * <p>注意：{@code arguments} 是模型生成文本，使用前需自行校验；流式下可能被截断。
 */
public interface IToolInvocation {

    /**
     * 工具调用回执 ID，用于把工具结果回填给平台。
     *
     * @return 调用 ID，非 null
     */
    String callId();

    /**
     * 被调用的函数名。
     *
     * @return 函数名，可为 null（平台未返回时）
     */
    String name();

    /**
     * 模型生成的入参 JSON 字符串。
     *
     * @return 入参 JSON 字符串，可为 null
     */
    String arguments();
}
