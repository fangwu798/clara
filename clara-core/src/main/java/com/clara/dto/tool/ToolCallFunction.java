package com.clara.dto.tool;

import lombok.Data;

/**
 * 函数调用详情：模型选中的函数名，以及模型为该函数生成的入参 JSON 字符串。
 *
 * <p>是什么、在哪一层：契约层的响应值对象（包 {@code com.clara.dto.tool}），是 {@link ToolCall} 的
 * {@code function} 子对象。请求侧声明函数签名的 {@link ToolSpecification} 与本类形状相反——
 * 那个描述「函数是什么」（{@code description} + JSON Schema {@code parameters}），
 * 本类描述「模型怎么调它」（{@code arguments}），因此两者不共用类型。
 *
 * <p>谁创建谁调用：由 {@code ProviderResponseDecoder} 在解码响应体时通过 Jackson 构造，调用方只读使用；
 * 不持有资源，不需要 {@code close()}。
 *
 * <p>使用方法：{@link #getName()} 取函数名以定位要执行的工具，{@link #getArguments()} 取 JSON 字符串
 * 并自行反序列化为该函数的入参对象。
 *
 * <p>注意：{@link #getArguments()} 是模型生成的文本，<b>不保证是合法 JSON</b>，也不保证符合请求侧声明的
 * JSON Schema；反序列化失败时应作为一次工具调用失败处理，而不是中断整轮对话。流式调用下该字段按分片追加，
 * 未收完时可能是被截断的 JSON 片段。
 */
@Data
public class ToolCallFunction {

    /**
     * 函数名，与请求侧 {@link ToolSpecification#getName()} 声明的名字对应。
     */
    private String name;

    /**
     * 入参 JSON 字符串，如 {@code {"city":"上海"}}；不保证是合法 JSON，使用前需自行校验。
     */
    private String arguments;
}
