package com.clara.tool;

import com.clara.enums.RoleEnum;
import com.clara.core.message.IContent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一工具执行结果：工具调用执行后回传给平台的结果，实现 {@link IContent} 以复用消息协议渲染。
 *
 * <p>是什么、在哪一层：契约层的工具结果值对象（包 {@code com.clara.tool}），是「工具执行产出」与
 * 「回传平台的消息」之间的统一桥梁。Chat Completions 下渲染为 {@code role=tool} 消息，
 * Responses 下渲染为 {@code function_call_output} 输入项。
 *
 * <p>使用方法：调用方执行 {@link ITool#invoke(String)} 得到结果后，用本类按 {@link IToolInvocation#callId()}
 * 回填，放进下一轮 {@code messages}。
 *
 * <p>注意：{@code output} 通常是 JSON 文本，直接回传即可，无需二次解析。
 */
public final class ToolResult implements IContent {

    /**
     * 对应的工具调用 ID，与 {@link IToolInvocation#callId()} 一致。
     */
    private final String callId;

    /**
     * 工具执行结果（通常为 JSON 文本）。
     */
    private final String output;

    /**
     * 创建工具执行结果。
     *
     * @param callId 对应的工具调用 ID，可为 null
     * @param output 工具执行结果，可为 null
     */
    public ToolResult(String callId, String output) {
        this.callId = callId;
        this.output = output;
    }

    public String getCallId() {
        return callId;
    }

    public String getOutput() {
        return output;
    }

    @Override
    public RoleEnum getRoleType() {
        return RoleEnum.TOOL;
    }

    @Override
    public Map<String, Object> toCompletionMessage() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", callId);
        message.put("content", output);
        return message;
    }

    @Override
    public List<Map<String, Object>> toResponseInputItems() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "function_call_output");
        item.put("call_id", callId);
        item.put("output", output);
        return Collections.singletonList(item);
    }
}
