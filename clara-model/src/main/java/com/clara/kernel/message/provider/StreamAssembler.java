package com.clara.kernel.message.provider;

import com.clara.core.annotation.ResponseAdapter;
import com.clara.enums.ModelProviderEnum;
import com.clara.core.model.IResponse;
import com.clara.exception.ClaraException;
import com.clara.dto.model.StreamEvent;
import com.clara.utils.deserialize.ResponseDecoderSupport;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 流式累加器：把 SSE 数据块解码为流事件，并把增量累加到同一个响应对象上。
 *
 * <p>协议差异通过抽象方法隔离：{@link #isTerminalData} / {@link #isTerminalEvent} / {@link #container} /
 * {@link #handleTerminal} 决定终态判定与取值根，{@link #convertField} / {@link #nonTextDelta} 决定字段转换
 * 与非文本增量的发射方式。
 */
public abstract class StreamAssembler<R extends IResponse> {

    /**
     * 累加目标，最终由 {@link #result()} 返回。
     */
    protected final R target;
    /**
     * 带 {@code streamPath} 的字段规则，按声明顺序发射事件。
     */
    protected final List<Rule> deltas;
    /**
     * 只有 {@code path} 的字段规则，每个数据块按路径取一次。
     */
    protected final List<Rule> singles;
    /**
     * 带 {@code terminalPath} 的字段规则，命中即写入结束原因。
     */
    protected final List<Rule> terminals;
    /**
     * 当前平台，用于错误归因。
     */
    protected final ModelProviderEnum provider;
    /**
     * 事件序号，从 0 开始递增。
     */
    private long sequence;
    /**
     * 是否已收到结束标记。
     */
    protected boolean terminated;
    /**
     * 流式失败信息；成功时为 null。
     */
    protected ClaraException failure;

    protected StreamAssembler(R target, List<Rule> deltas, List<Rule> singles, List<Rule> terminals, ModelProviderEnum provider) {
        this.target = target;
        this.deltas = deltas;
        this.singles = singles;
        this.terminals = terminals;
        this.provider = provider;
    }

    /**
     * 处理一条 SSE 数据块。
     */
    public List<StreamEvent> accept(String sseEvent, String sseData) {
        if (terminated || sseData == null || sseData.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String data = sseData.trim();
        if (isTerminalData(sseEvent, data)) {
            terminated = true;
            return Collections.emptyList();
        }
        JsonNode root = ResponseDecoderSupport.parse(data, provider);
        if (root == null || !root.isObject()) {
            if (isTerminalEvent(sseEvent)) {
                terminated = true;
            }
            return Collections.emptyList();
        }
        JsonNode container = container(root);
        applySingleRules(container);
        applyTerminalRules(container);
        List<StreamEvent> events = collectDeltas(sseEvent, root);
        handleTerminal(sseEvent, root);
        return events;
    }

    private void applySingleRules(JsonNode container) {
        for (Rule rule : singles) {
            JsonNode node = ResponseDecoderSupport.valueAt(container, rule.path);
            if (ResponseDecoderSupport.present(node)) {
                ResponseDecoderSupport.write(rule.field, target, convertField(rule.field, node, provider));
            }
        }
    }

    private void applyTerminalRules(JsonNode container) {
        for (Rule rule : terminals) {
            JsonNode node = ResponseDecoderSupport.valueAt(container, rule.terminalPath);
            if (ResponseDecoderSupport.present(node)) {
                ResponseDecoderSupport.write(rule.field, target,
                        ResponseDecoderSupport.convert(rule.field.getGenericType(), node, provider));
            }
        }
    }

    private List<StreamEvent> collectDeltas(String sseEvent, JsonNode root) {
        List<StreamEvent> events = new ArrayList<>();
        for (Rule rule : deltas) {
            if (rule.streamEvent.isEmpty() || rule.streamEvent.equals(sseEvent)) {
                StreamEvent event = deltaEvent(rule, root);
                if (event != null) {
                    events.add(event);
                }
            }
        }
        return events;
    }

    private StreamEvent deltaEvent(Rule rule, JsonNode root) {
        JsonNode node = ResponseDecoderSupport.valueAt(root, rule.streamPath);
        if (!ResponseDecoderSupport.present(node)) {
            return null;
        }
        if (!node.isTextual()) {
            return nonTextDelta(rule, node);
        }
        String text = node.textValue();
        if (text.isEmpty()) {
            return null;
        }
        ResponseDecoderSupport.appendText(rule.field, target, text);
        return new StreamEvent(rule.adapter.event(), nextSequence(), text);
    }

    /**
     * 分配下一个事件序号。
     */
    protected long nextSequence() {
        return sequence++;
    }

    /**
     * 判断当前数据块是否为协议的终止数据。
     */
    protected abstract boolean isTerminalData(String sseEvent, String data);

    /**
     * 判断当前 SSE 事件名是否为协议的终止事件。
     */
    protected abstract boolean isTerminalEvent(String sseEvent);

    /**
     * 求 singles / terminals 的取值根。
     */
    protected abstract JsonNode container(JsonNode root);

    /**
     * 终态后处理。
     */
    protected abstract void handleTerminal(String sseEvent, JsonNode root);

    /**
     * 按字段类型转换节点值。
     */
    protected abstract Object convertField(Field field, JsonNode node, ModelProviderEnum provider);

    /**
     * 非文本增量的协议特定发射；返回 null 表示不发射。
     */
    protected abstract StreamEvent nonTextDelta(Rule rule, JsonNode node);

    /**
     * 取流式过程的失败信息。
     */
    public ClaraException failure() {
        return failure;
    }

    /**
     * 是否已收到结束标记。
     */
    public boolean isTerminated() {
        return terminated;
    }

    /**
     * 取累加完成的响应对象。
     */
    public R result() {
        return target;
    }

    /**
     * 字段与命中规则的组合。
     */
    public static final class Rule {
        public final Field field;
        public final ResponseAdapter adapter;
        private final String path;
        private final String streamPath;
        private final String streamEvent;
        private final String terminalPath;

        Rule(Field field, ResponseAdapter adapter, String path, String streamPath, String streamEvent, String terminalPath) {
            this.field = field;
            this.adapter = adapter;
            this.path = path;
            this.streamPath = streamPath;
            this.streamEvent = streamEvent;
            this.terminalPath = terminalPath;
        }
    }
}
