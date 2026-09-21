package com.clara.tool;

import lombok.Data;

/**
 * 工具调用条目：模型在响应中要求调用的一次工具，对应 OpenAI 兼容家族 {@code choices[0].message.tool_calls[]}
 * 的元素，或流式下 {@code choices[0].delta.tool_calls[]} 的分片。
 *
 * <p>是什么、在哪一层：契约层的响应值对象（包 {@code com.clara.tool}），与请求侧的
 * {@link ToolContext} 同属「工具」这一语义域，但形状不同——{@link ToolContext} 描述「有哪些工具可用」
 * （调用前声明，函数带描述与 JSON Schema），本类描述「模型要调用哪个工具」以及「传什么参数」
 * （调用后返回，入口是 {@code id} 与 {@code arguments}），因此两者不共用类型。
 *
 * <p>谁创建谁调用：由 {@code ProviderResponseDecoder} 在解码响应体时通过 Jackson 构造（因此需要无参构造器），
 * 调用方只读使用。本类不持有资源，不需要 {@code close()}。
 *
 * <p>使用方法：
 * <ol>
 *   <li>从统一响应对象的 {@code toolCalls} 字段取回本类实例列表；</li>
 *   <li>用 {@link #getId()} 作为本次调用的回执标识，把工具结果按同一 {@code id} 回填给平台；</li>
 *   <li>用 {@link #getFunction()} 取函数名与入参 JSON 字符串。</li>
 * </ol>
 *
 * <p>注意：{@code arguments} 是模型生成的 JSON 字符串，可能不是合法 JSON，使用前必须自行校验；
 * 流式调用下同一 {@code id} 的参数会分片到达，分片的合并由调用方处理。
 */
@Data
public class ToolCall {

    /**
     * 工具调用 ID，用于把工具执行结果回填给平台。
     *
     * <p>取值由平台生成（如 {@code call_0}、{@code call_abc123}）；流式下首个分片携带，后续分片可能省略。
     */
    private String id;

    /**
     * 工具类型，OpenAI 兼容家族恒为 {@code function}。
     *
     * <p>取值与请求侧 {@link ToolContext#getType()} 同源：对话为 {@code function}，
     * 图片生成的联网搜索为 {@code web_search}（此时没有函数详情）。
     */
    private String type;

    /**
     * 函数调用详情：函数名与 JSON 字符串形式的入参。
     */
    private ToolCallFunction function;
}
