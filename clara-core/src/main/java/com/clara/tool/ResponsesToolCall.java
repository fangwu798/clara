package com.clara.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OpenAI Responses 协议的工具调用条目：响应 {@code output[]} 里 {@code type=function_call} 的扁平结构。
 *
 * <p>为什么单独成类：Responses 的 function_call 是扁平结构
 * {@code {"type":"function_call","call_id":"...","name":"...","arguments":"..."}}，
 * 与 Chat Completions 的 {@code {"id":"...","type":"function","function":{"name":"...","arguments":"..."}}}
 * 差异较大，无法用同一套字段映射。本类作为 Responses 协议下的独立工具调用类型，与
 * {@link ToolCall}（Chat Completions）并列，{@code type} 恒为 {@code function_call}，用于区分两种模式。
 */
@Data
@NoArgsConstructor
public final class ResponsesToolCall {

    /**
     * 条目类型，恒为 {@code function_call}。
     */
    @JsonProperty("type")
    private String type;

    /**
     * 工具调用回执 ID，用于把工具结果回填给平台。
     */
    @JsonProperty("call_id")
    private String callId;

    /**
     * 被调用的函数名。
     */
    @JsonProperty("name")
    private String name;

    /**
     * 模型生成的入参 JSON 字符串。
     */
    @JsonProperty("arguments")
    private String arguments;
}
