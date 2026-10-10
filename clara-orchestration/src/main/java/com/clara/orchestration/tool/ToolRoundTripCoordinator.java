package com.clara.orchestration.tool;

import com.clara.core.message.IContent;
import com.clara.core.skill.IToolInvoker;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.dto.tool.ResponsesToolCall;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallContent;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.dto.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具往返协调器：检测模型响应中的工具调用，逐个执行并按 {@code callId} 构造结果消息回填。
 *
 * <p>本地工具经 {@link IToolInvoker} 执行；外部工具由 clara-mcp 在 {@code IToolInvoker} 侧联动。编排层负责
 * 把「助手要求调用的工具消息」与「工具结果消息」按调用一一对应地写回历史，随后回到装配步骤再次请求。
 */
public final class ToolRoundTripCoordinator {

    private final IToolInvoker toolInvoker;

    /**
     * 创建工具往返协调器。
     *
     * @param toolInvoker 工具调用执行入口，不可为 null
     */
    public ToolRoundTripCoordinator(IToolInvoker toolInvoker) {
        if (toolInvoker == null) {
            throw new IllegalArgumentException("toolInvoker must not be null");
        }
        this.toolInvoker = toolInvoker;
    }

    /**
     * 判断一次响应是否要求调用工具。
     *
     * @param response 模型响应，可为 null
     * @return 存在工具调用时返回 {@code true}
     */
    public boolean hasToolCalls(HttpResponse response) {
        if (response == null) {
            return false;
        }
        return (response.getToolCalls() != null && !response.getToolCalls().isEmpty())
                || (response.getResponsesToolCalls() != null
                        && !response.getResponsesToolCalls().isEmpty());
    }

    /**
     * 执行响应中的全部工具调用，产出可写回历史的消息组合。
     *
     * <p>返回顺序为「助手工具调用消息」在前、「工具结果消息」按调用顺序在后；每条结果消息的
     * {@code tool_call_id} 与助手侧调用标识一一对应。
     *
     * @param response 模型响应，不可为 null
     * @return 待追加的消息列表，非 null
     */
    public List<IContent> perform(HttpResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }
        List<ToolCall> calls = assistantCalls(response);
        List<IContent> messages = new ArrayList<IContent>();
        messages.add(new ToolCallContent(calls));
        for (ToolCall call : calls) {
            ToolResult result = toolInvoker.invoke(call);
            messages.add(new ToolContent(result.getCallId(), result.getOutput()));
        }
        return messages;
    }

    /**
     * 把 Chat Completions 与 Responses 两种协议的工具调用统一为助手侧调用列表。
     */
    private static List<ToolCall> assistantCalls(HttpResponse response) {
        if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
            return response.getToolCalls();
        }
        List<ToolCall> calls = new ArrayList<ToolCall>();
        for (ResponsesToolCall call : response.getResponsesToolCalls()) {
            ToolCallFunction function = new ToolCallFunction();
            function.setName(call.name());
            function.setArguments(call.arguments());
            ToolCall unified = new ToolCall();
            unified.setId(call.callId());
            unified.setType("function");
            unified.setFunction(function);
            calls.add(unified);
        }
        return calls;
    }
}
