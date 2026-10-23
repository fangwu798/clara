package com.clara.kernel.message.provider.messages;

import com.clara.core.model.IResponse;
import com.clara.dto.model.StreamEvent;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.StreamEventEnum;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.message.provider.StreamAssembler;
import com.clara.utils.ExceptionUtils;
import com.clara.utils.deserialize.ResponseDecoderSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Anthropic Messages 协议的流式累加器。
 *
 * <p>与 Chat Completions 的差异：
 * <ul>
 *   <li>没有 {@code [DONE]}，以具名事件 {@code message_stop} 收尾，错误以 {@code error} 事件下发；</li>
 *   <li>{@code message_start} 把响应元信息包在 {@code message} 子对象里，因此 singles 的取值根会下钻一层；</li>
 *   <li>工具调用不是数组分片，而是 {@code content_block_start(tool_use)} + {@code input_json_delta} +
 *       {@code content_block_stop}，这里按内容块下标拼装，并把分片转成观察者熟悉的
 *       {@code [{index,id,function:{name,arguments}}]} 负载。</li>
 * </ul>
 */
public final class MessagesStreamAssembler<R extends IResponse> extends StreamAssembler<R> {

    /**
     * 按内容块下标累积的工具调用。
     */
    private final Map<Integer, ToolCall> toolCalls = new TreeMap<>();

    /**
     * 按内容块下标累积的工具入参 JSON 片段。
     */
    private final Map<Integer, StringBuilder> arguments = new LinkedHashMap<>();

    /**
     * 本轮事件处理过程中新增的工具增量事件，由 {@link #accept} 合并到返回值中。
     */
    private final List<StreamEvent> pending = new ArrayList<>();

    MessagesStreamAssembler(R target, List<Rule> deltas, List<Rule> singles, List<Rule> terminals,
            ModelProviderEnum provider) {
        super(target, deltas, singles, terminals, provider);
    }

    @Override
    public List<StreamEvent> accept(String sseEvent, String sseData) {
        pending.clear();
        List<StreamEvent> events = super.accept(sseEvent, sseData);
        if (pending.isEmpty()) {
            return events;
        }
        List<StreamEvent> merged = new ArrayList<>(events.size() + pending.size());
        merged.addAll(events);
        merged.addAll(pending);
        pending.clear();
        return merged;
    }

    @Override
    protected boolean isTerminalData(String sseEvent, String data) {
        return false;
    }

    @Override
    protected boolean isTerminalEvent(String sseEvent) {
        return "message_stop".equals(sseEvent) || "error".equals(sseEvent);
    }

    @Override
    protected JsonNode container(JsonNode root) {
        JsonNode message = root.get("message");
        return message != null && message.isObject() ? message : root;
    }

    @Override
    protected void handleTerminal(String sseEvent, JsonNode root) {
        String type = root.path("type").asText("");
        if ("error".equals(type) || "error".equals(sseEvent)) {
            JsonNode error = root.get("error");
            failure = ExceptionUtils.providerEventFailure(provider,
                    text(error == null ? null : error.get("type")),
                    text(error == null ? null : error.get("message")));
            terminated = true;
            return;
        }
        if ("content_block_start".equals(type)) {
            startToolUse(root);
        } else if ("content_block_delta".equals(type)) {
            appendToolArguments(root);
        } else if ("message_stop".equals(type)) {
            finishToolCalls();
            terminated = true;
        }
    }

    @Override
    protected Object convertField(Field field, JsonNode node, ModelProviderEnum provider) {
        if (MessagesToolCalls.isToolCallList(field)) {
            return MessagesToolCalls.fromContent(node);
        }
        return ResponseDecoderSupport.convert(field.getGenericType(), node, provider);
    }

    @Override
    protected StreamEvent nonTextDelta(Rule rule, JsonNode node) {
        return new StreamEvent(rule.adapter.event(), nextSequence(), node.toString());
    }

    private void startToolUse(JsonNode root) {
        JsonNode block = root.get("content_block");
        if (block == null || !"tool_use".equals(block.path("type").asText())) {
            return;
        }
        int index = root.path("index").asInt();
        ToolCall call = new ToolCall();
        call.setId(text(block.get("id")));
        call.setType("function");
        ToolCallFunction function = new ToolCallFunction();
        function.setName(text(block.get("name")));
        function.setArguments("");
        call.setFunction(function);
        toolCalls.put(index, call);
        arguments.put(index, new StringBuilder());
        emitToolDelta(index, call, "");
    }

    private void appendToolArguments(JsonNode root) {
        JsonNode delta = root.get("delta");
        if (delta == null || !"input_json_delta".equals(delta.path("type").asText())) {
            return;
        }
        int index = root.path("index").asInt();
        ToolCall call = toolCalls.get(index);
        if (call == null) {
            return;
        }
        String partial = delta.path("partial_json").asText("");
        arguments.computeIfAbsent(index, key -> new StringBuilder()).append(partial);
        emitToolDelta(index, call, partial);
    }

    private void emitToolDelta(int index, ToolCall call, String partial) {
        String id = call.getId();
        String name = call.getFunction() == null ? null : call.getFunction().getName();
        pending.add(new StreamEvent(StreamEventEnum.TOOL_ARGUMENT_DELTA, nextSequence(),
                MessagesToolCalls.fragment(index, id, name, partial), id, name, null, null));
    }

    private void finishToolCalls() {
        HttpResponse response = response();
        if (response == null) {
            return;
        }
        if (!toolCalls.isEmpty()) {
            List<ToolCall> calls = new ArrayList<>();
            for (Map.Entry<Integer, ToolCall> entry : toolCalls.entrySet()) {
                StringBuilder buffer = arguments.get(entry.getKey());
                String json = buffer == null || buffer.length() == 0 ? "{}" : buffer.toString();
                entry.getValue().getFunction().setArguments(json);
                calls.add(entry.getValue());
            }
            response.setToolCalls(calls);
        }
        if (response.getInputTokens() != null && response.getOutputTokens() != null) {
            response.setTotalTokens(response.getInputTokens() + response.getOutputTokens());
        }
    }

    private HttpResponse response() {
        return target instanceof HttpResponse ? (HttpResponse) target : null;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
