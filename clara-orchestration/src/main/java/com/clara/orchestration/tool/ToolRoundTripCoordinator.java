package com.clara.orchestration.tool;

import com.clara.core.message.IContent;
import com.clara.core.telemetry.Telemetry;
import com.clara.core.telemetry.TelemetrySpan;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.core.skill.IToolInvoker;
import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.dto.tool.ResponsesToolCall;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallContent;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.dto.tool.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具往返协调器：检测模型响应中的工具调用，逐个执行并按 {@code callId} 构造结果消息回填。
 *
 * <p>本地工具经 {@link IToolInvoker} 执行；外部工具由 clara-mcp 在 {@code IToolInvoker} 侧联动。编排层负责
 * 把「助手要求调用的工具消息」与「工具结果消息」按调用一一对应地写回历史，随后回到装配步骤再次请求。
 */
public final class ToolRoundTripCoordinator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IToolInvoker toolInvoker;
    /** 链路追踪入口；未接观测端时为空实现。 */
    private final Telemetry telemetry;

    /**
     * 创建工具往返协调器。
     *
     * @param toolInvoker 工具调用执行入口，不可为 null
     */
    public ToolRoundTripCoordinator(IToolInvoker toolInvoker) {
        this(toolInvoker, Telemetry.noop());
    }

    /**
     * 创建带链路追踪的工具往返协调器。
     *
     * @param toolInvoker 工具调用执行入口，不可为 null
     * @param telemetry   链路追踪入口，可为 null（按空实现处理）
     */
    public ToolRoundTripCoordinator(IToolInvoker toolInvoker, Telemetry telemetry) {
        if (toolInvoker == null) {
            throw new IllegalArgumentException("toolInvoker must not be null");
        }
        this.toolInvoker = toolInvoker;
        this.telemetry = telemetry == null ? Telemetry.noop() : telemetry;
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
        return response.getToolCalls() != null && !response.getToolCalls().isEmpty()
                || response.getResponsesToolCalls() != null
                        && !response.getResponsesToolCalls().isEmpty();
    }

    /**
     * 执行响应中的全部工具调用，产出可写回历史的消息组合。
     *
     * <p>返回顺序为「助手工具调用消息」在前、「工具结果消息」按调用顺序在后；每条结果消息的
     * {@code tool_call_id} 与助手侧调用标识一一对应。
     *
     * <p>容错语义：单个工具调用失败（含入参校验不通过、脚本执行失败等）不再中断整轮，而是把错误
     * 文本作为该调用的结果回填给模型，使其能据错误信息自行修正后重试。
     *
     * @param response 模型响应，不可为 null
     * @return 待追加的消息列表，非 null
     */
    public List<IContent> perform(HttpResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }
        List<ToolCall> calls = assistantCalls(response);
        List<IContent> messages = new ArrayList<>();
        messages.add(new ToolCallContent(calls));
        for (ToolCall call : calls) {
            messages.add(invokeSafely(call));
        }
        return messages;
    }

    /**
     * 检查本轮工具结果是否要求「先让用户确认再继续」。
     *
     * <p>工作流遇到 HUMAN 节点时会在结果 JSON 里写 {@code waitingForUser=true} 与用户可见的
     * {@code display} 文本。编排层据此结束当前轮，把 display 返回给用户；用户的下一轮输入才可能触发
     * 工作流恢复，从机制上避免模型在同一次工具往返里自行确认高价操作。
     *
     * @param messages 本轮产出的消息，可为 null
     * @return 需要暂停时返回展示文本，否则返回 null
     */
    public String userInputRequest(List<IContent> messages) {
        if (messages == null) {
            return null;
        }
        for (IContent message : messages) {
            if (!(message instanceof ToolContent)) {
                continue;
            }
            String output = ((ToolContent) message).getContent();
            if (output == null || output.trim().isEmpty()) {
                continue;
            }
            try {
                JsonNode root = JSON.readTree(output);
                JsonNode waiting = root == null ? null : root.get("waitingForUser");
                if (waiting == null || !waiting.asBoolean(false)) {
                    continue;
                }
                JsonNode display = root.get("display");
                return display != null && display.isTextual() && !display.textValue().trim().isEmpty()
                        ? display.textValue() : "工作流已暂停，等待人工确认。";
            } catch (JsonProcessingException ignored) {
                // 非 JSON 工具结果不参与暂停判断
            }
        }
        return null;
    }

    /**
     * 执行单个工具调用并把结果封装为工具结果消息；失败时以错误文本回填，不向上抛出。
     */
    private IContent invokeSafely(ToolCall call) {
        try (TelemetrySpan span = telemetry.startSpan("tool.call", TelemetrySpanKindEnum.TOOL)) {
            span.attribute("tool", toolName(call))
                    .attribute("call_id", call == null ? null : call.getId());
            try {
                ToolResult result = toolInvoker.invoke(call);
                span.attribute("output_chars", result == null || result.getOutput() == null
                        ? 0 : result.getOutput().length());
                return new ToolContent(result.getCallId(), result.getOutput());
            } catch (RuntimeException error) {
                span.error(error);
                return new ToolContent(call.getId(), errorPayload(error));
            }
        }
    }

    /**
     * 取工具名用于链路属性；调用结构不完整时返回 null，避免观测代码自己抛异常。
     *
     * @param call 模型给出的工具调用，可为 null
     * @return 工具名或 null
     */
    private static String toolName(ToolCall call) {
        return call == null || call.getFunction() == null ? null : call.getFunction().getName();
    }

    /**
     * 把工具调用失败序列化为 {@code {"error":"..."}} 文本，回填给模型作为可读的失败观察。
     */
    private static String errorPayload(RuntimeException error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", errorMessage(error));
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"工具调用失败\"}";
        }
    }

    private static String errorMessage(RuntimeException error) {
        if (error instanceof ClaraException) {
            ClaraError claraError = ((ClaraException) error).getError();
            return "[" + claraError.getCode() + "/" + claraError.getCategory() + "] "
                    + claraError.getSafeMessage();
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    /**
     * 把 Chat Completions 与 Responses 两种协议的工具调用统一为助手侧调用列表。
     */
    private static List<ToolCall> assistantCalls(HttpResponse response) {
        if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
            return response.getToolCalls();
        }
        List<ToolCall> calls = new ArrayList<>();
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
