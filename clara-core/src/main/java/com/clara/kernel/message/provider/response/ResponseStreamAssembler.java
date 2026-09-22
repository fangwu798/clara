package com.clara.utils.deserialize;

import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.api.IResponse;
import com.clara.kernel.stream.StreamEvent;
import com.clara.tool.ResponsesToolCall;
import com.clara.utils.ExceptionUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Responses 协议的流式累加器。
 *
 * <p>以 {@code response.completed} / {@code response.incomplete} / {@code response.failed} 作为终态事件，
 * singles / terminals 的取值根回退到 {@code response} 子对象，{@code response.failed} 时提取错误；工具调用
 * 在 {@code response.completed} 里从 {@code response.output} 过滤 {@code function_call} 解析为独立类型
 * {@link ResponsesToolCall}。
 */
public final class ResponseStreamAssembler<R extends IResponse> extends StreamAssembler<R> {

    ResponseStreamAssembler(R target, List<Rule> deltas, List<Rule> singles, List<Rule> terminals, ModelProviderEnum provider) {
        super(target, deltas, singles, terminals, provider);
    }

    @Override
    protected boolean isTerminalData(String sseEvent, String data) {
        return false;
    }

    @Override
    protected boolean isTerminalEvent(String sseEvent) {
        return "response.completed".equals(sseEvent) || "response.incomplete".equals(sseEvent) || "response.failed".equals(sseEvent);
    }

    @Override
    protected JsonNode container(JsonNode root) {
        JsonNode response = root.get("response");
        if (response != null && response.isObject()) {
            return response;
        }
        return root;
    }

    @Override
    protected void handleTerminal(String sseEvent, JsonNode root) {
        if ("response.failed".equals(sseEvent)) {
            failure = ExceptionUtils.providerEventFailure(provider, extractErrorCode(root), extractErrorMessage(root));
        }
        if (isTerminalEvent(sseEvent)) {
            terminated = true;
        }
    }

    @Override
    protected Object convertField(Field field, JsonNode node, ModelProviderEnum provider) {
        if (ResponseToolCallSupport.isResponsesToolCallList(field)) {
            return ResponseToolCallSupport.convertResponsesToolCalls(node);
        }
        return ResponseDecoderSupport.convert(field.getGenericType(), node, provider);
    }

    @Override
    protected StreamEvent nonTextDelta(Rule rule, JsonNode node) {
        if (ResponseToolCallSupport.isResponsesToolCallList(rule.field)) {
            List<ResponsesToolCall> calls = ResponseToolCallSupport.convertResponsesToolCalls(node);
            if (calls != null && !calls.isEmpty()) {
                ResponseDecoderSupport.write(rule.field, target, calls);
                return new StreamEvent(rule.adapter.event(), nextSequence(), ResponseToolCallSupport.toolCallsPayload(calls));
            }
            return null;
        }
        return new StreamEvent(rule.adapter.event(), nextSequence(), node.toString());
    }

    private static String extractErrorCode(JsonNode root) {
        JsonNode error = errorNode(root);
        if (error != null) {
            JsonNode code = error.get("code");
            if (code != null && code.isTextual() && !code.textValue().isEmpty()) {
                return code.textValue();
            }
        }
        return null;
    }

    private static String extractErrorMessage(JsonNode root) {
        JsonNode error = errorNode(root);
        if (error != null) {
            JsonNode message = error.get("message");
            if (message != null && message.isTextual() && !message.textValue().isEmpty()) {
                return message.textValue();
            }
        }
        return null;
    }

    private static JsonNode errorNode(JsonNode root) {
        JsonNode error = root.get("error");
        if (error != null && error.isObject()) {
            return error;
        }
        JsonNode response = root.get("response");
        if (response != null && response.isObject()) {
            error = response.get("error");
            if (error != null && error.isObject()) {
                return error;
            }
        }
        return null;
    }
}
