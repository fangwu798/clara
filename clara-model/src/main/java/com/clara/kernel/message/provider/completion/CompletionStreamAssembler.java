package com.clara.kernel.message.provider.completion;

import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.api.IResponse;
import com.clara.kernel.message.provider.StreamAssembler;
import com.clara.kernel.stream.StreamEvent;
import com.clara.utils.deserialize.ResponseDecoderSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Chat Completions 协议的流式累加器。
 *
 * <p>以 {@code [DONE]} 作为终止标记，无具名终态事件，singles / terminals 直接以数据根为取值根，非文本增量
 * 直接以 JSON 文本发射（工具调用分片 {@code delta.tool_calls} 交给观察者按 index 拼装）。
 */
public final class CompletionStreamAssembler<R extends IResponse> extends StreamAssembler<R> {

    CompletionStreamAssembler(R target, List<Rule> deltas, List<Rule> singles, List<Rule> terminals, ModelProviderEnum provider) {
        super(target, deltas, singles, terminals, provider);
    }

    @Override
    protected boolean isTerminalData(String sseEvent, String data) {
        return "[DONE]".equals(data);
    }

    @Override
    protected boolean isTerminalEvent(String sseEvent) {
        return false;
    }

    @Override
    protected JsonNode container(JsonNode root) {
        return root;
    }

    @Override
    protected void handleTerminal(String sseEvent, JsonNode root) {
        // Chat Completions 无终态后处理
    }

    @Override
    protected Object convertField(Field field, JsonNode node, ModelProviderEnum provider) {
        return ResponseDecoderSupport.convert(field.getGenericType(), node, provider);
    }

    @Override
    protected StreamEvent nonTextDelta(Rule rule, JsonNode node) {
        return new StreamEvent(rule.adapter.event(), nextSequence(), node.toString());
    }
}
