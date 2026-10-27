package com.clara.memory.persistence;

import com.clara.core.message.IContent;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.TokenUsage;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallContent;
import com.clara.dto.tool.ToolCallFunction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话快照与持久化字节之间的编解码器。
 *
 * <p>以 JSON 字节承载「版本 + 消息列表」；消息复用 {@link IContent#toCompletionMessage()} 的线格式渲染，
 * 反序列化时按 {@code role} 与 {@code tool_calls} 还原为具体消息实现，隔离底层序列化细节。
 */
public final class ConversationCodec {

    private static final String FIELD_CONTENT = "content";

    /**
     * 持久化专用的 token 用量字段；不进入协议渲染。
     */
    private static final String FIELD_USAGE = "clara_usage";

    /**
     * 持久化专用的模型标识字段（形如 {@code PROVIDER/model}）；不进入协议渲染。
     */
    private static final String FIELD_MODEL = "clara_model";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {
            };

    /**
     * 把快照编码为持久化字节。
     *
     * @param snapshot 会话快照，不可为 null
     * @return JSON 字节，非 null
     */
    public byte[] encode(ConversationSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", snapshot.version());
        List<Map<String, Object>> messages = new ArrayList<>();
        for (IContent message : snapshot.messages()) {
            Map<String, Object> wire = new LinkedHashMap<>(message.toCompletionMessage());
            attachMetadata(wire, message);
            messages.add(wire);
        }
        root.put("messages", messages);
        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw ConversationStoreErrors.corrupted();
        }
    }

    /**
     * 把持久化字节解码为快照。
     *
     * @param bytes 持久化字节，不可为 null
     * @return 会话快照，非 null
     */
    @SuppressWarnings("unchecked")
    public ConversationSnapshot decode(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        try {
            Map<String, Object> root = MAPPER.readValue(bytes, MAP_TYPE);
            Object versionValue = root.get("version");
            if (!(versionValue instanceof Number)) {
                throw ConversationStoreErrors.corrupted();
            }
            long version = ((Number) versionValue).longValue();
            Object messagesValue = root.get("messages");
            if (!(messagesValue instanceof List)) {
                throw ConversationStoreErrors.corrupted();
            }
            List<IContent> messages = new ArrayList<>();
            for (Object item : (List<Object>) messagesValue) {
                if (!(item instanceof Map)) {
                    throw ConversationStoreErrors.corrupted();
                }
                messages.add(decodeMessage((Map<String, Object>) item));
            }
            return new ConversationSnapshot(version, messages);
        } catch (IOException e) {
            throw ConversationStoreErrors.corrupted();
        }
    }

    @SuppressWarnings("unchecked")
    private static IContent decodeMessage(Map<String, Object> message) {
        Object roleValue = message.get("role");
        if (!(roleValue instanceof String)) {
            throw ConversationStoreErrors.corrupted();
        }
        String role = (String) roleValue;
        if ("system".equals(role)) {
            return new SystemContent(text(message.get(FIELD_CONTENT)));
        }
        if ("user".equals(role)) {
            return new UserContent(text(message.get(FIELD_CONTENT)));
        }
        if ("tool".equals(role)) {
            return new ToolContent(text(message.get("tool_call_id")), text(message.get(FIELD_CONTENT)));
        }
        if ("assistant".equals(role)) {
            Object toolCalls = message.get("tool_calls");
            if (toolCalls instanceof List && !((List<Object>) toolCalls).isEmpty()) {
                return new ToolCallContent(text(message.get(FIELD_CONTENT)),
                        decodeToolCalls((List<Object>) toolCalls));
            }
            return new AssistantContent(text(message.get(FIELD_CONTENT)),
                    decodeUsage(message.get(FIELD_USAGE)), text(message.get(FIELD_MODEL)));
        }
        throw ConversationStoreErrors.corrupted();
    }

    @SuppressWarnings("unchecked")
    private static List<ToolCall> decodeToolCalls(List<Object> rawCalls) {
        List<ToolCall> calls = new ArrayList<>(rawCalls.size());
        for (Object item : rawCalls) {
            if (!(item instanceof Map)) {
                throw ConversationStoreErrors.corrupted();
            }
            Map<String, Object> raw = (Map<String, Object>) item;
            ToolCall call = new ToolCall();
            call.setId(text(raw.get("id")));
            call.setType(text(raw.get("type")));
            Object functionValue = raw.get("function");
            if (functionValue instanceof Map) {
                Map<String, Object> rawFunction = (Map<String, Object>) functionValue;
                ToolCallFunction function = new ToolCallFunction();
                function.setName(text(rawFunction.get("name")));
                function.setArguments(text(rawFunction.get("arguments")));
                call.setFunction(function);
            }
            calls.add(call);
        }
        return calls;
    }


    /**
     * 把助手消息的 token 用量与模型标识写入持久化副本。
     *
     * <p>这些字段只存在于落盘副本：协议渲染（{@code toCompletionMessage()}）不包含它们，
     * 因此不会把本地统计信息发给模型平台。
     *
     * @param wire    持久化消息副本，非 null
     * @param message 原始消息，非 null
     */
    private static void attachMetadata(Map<String, Object> wire, IContent message) {
        if (!(message instanceof AssistantContent)) {
            return;
        }
        AssistantContent assistant = (AssistantContent) message;
        TokenUsage usage = assistant.getUsage();
        if (assistant.getModel() != null && !assistant.getModel().trim().isEmpty()) {
            wire.put(FIELD_MODEL, assistant.getModel());
        }
        if (usage == null) {
            return;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("input", usage.getInputTokens());
        value.put("output", usage.getOutputTokens());
        if (usage.getTotalTokens() != null) {
            value.put("total", usage.getTotalTokens());
        }
        if (usage.getCacheTokens() != null) {
            value.put("cache", usage.getCacheTokens());
        }
        wire.put(FIELD_USAGE, value);
    }

    /**
     * 还原持久化的 token 用量；字段缺失或非法时返回 null，兼容旧历史文件。
     *
     * @param value 持久化字段值，可为 null
     * @return 用量，无法还原时为 null
     */
    @SuppressWarnings("unchecked")
    private static TokenUsage decodeUsage(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        Map<String, Object> usage = (Map<String, Object>) value;
        Integer input = integer(usage.get("input"));
        Integer output = integer(usage.get("output"));
        if (input == null || output == null) {
            return null;
        }
        try {
            return new TokenUsage(input, output, integer(usage.get("total")), integer(usage.get("cache")));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Integer integer(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
