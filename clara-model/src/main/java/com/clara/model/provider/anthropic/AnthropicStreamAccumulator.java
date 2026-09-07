package com.clara.model.provider.anthropic;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.Content;
import com.clara.model.api.FinishReason;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.Role;
import com.clara.model.api.TextContent;
import com.clara.model.api.TokenUsage;
import com.clara.model.api.ToolCallContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 累积Anthropic流的增量状态。
 */
final class AnthropicStreamAccumulator {
    private final AnthropicChatConfig config;
    private final int maxContentBlocks;
    private final int maxAccumulatedChars;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Integer, Block> blocks = new TreeMap<Integer, Block>();
    private String id;
    private String model;
    private String stopSequence;
    private FinishReason finishReason;
    private Integer inputTokens;
    private Integer outputTokens;
    private int accumulatedChars;
    private boolean started;
    private boolean messageDelta;
    private boolean complete;

    AnthropicStreamAccumulator(AnthropicChatConfig config, int maxContentBlocks,
            int maxAccumulatedChars) {
        if (config == null) { throw new IllegalArgumentException("config must not be null"); }
        if (maxContentBlocks <= 0 || maxAccumulatedChars <= 0) {
            throw new IllegalArgumentException("stream limits must be positive");
        }
        this.config = config;
        this.maxContentBlocks = maxContentBlocks;
        this.maxAccumulatedChars = maxAccumulatedChars;
    }

    AnthropicStreamChunk accept(String eventName, String data) {
        if (eventName == null || data == null) {
            throw new IllegalArgumentException("eventName and data must not be null");
        }
        if (complete) { throw protocol(); }
        try {
            JsonNode root = mapper.readTree(data);
            if (root == null || !root.isObject()) { throw protocol(); }
            String type = requiredText(root, "type");
            if (!type.equals(eventName)) { throw protocol(); }
            if ("ping".equals(type)) return AnthropicStreamChunk.empty();
            if ("error".equals(type)) { throw providerError(root.get("error")); }
            if ("message_start".equals(type)) return messageStart(root);
            requireStarted();
            if ("content_block_start".equals(type)) return blockStart(root);
            if ("content_block_delta".equals(type)) return blockDelta(root);
            if ("content_block_stop".equals(type)) return blockStop(root);
            if ("message_delta".equals(type)) return messageDelta(root);
            if ("message_stop".equals(type)) return messageStop();
            throw protocol();
        } catch (ClaraException exception) {
            throw exception;
        } catch (IOException exception) {
            throw protocol();
        } catch (RuntimeException exception) {
            throw protocol();
        }
    }

    boolean isComplete() { return complete; }

    private AnthropicStreamChunk messageStart(JsonNode root) {
        if (started) { throw protocol(); }
        JsonNode message = root.get("message");
        if (message == null || !message.isObject()
                || !"assistant".equals(requiredText(message, "role"))) { throw protocol(); }
        JsonNode content = message.get("content");
        if (content == null || !content.isArray() || content.size() != 0) { throw protocol(); }
        id = optionalText(message, "id");
        model = optionalText(message, "model");
        JsonNode usage = message.get("usage");
        if (usage == null || !usage.isObject()) { throw protocol(); }
        inputTokens = nonNegativeInt(usage, "input_tokens");
        started = true;
        return AnthropicStreamChunk.empty();
    }

    private AnthropicStreamChunk blockStart(JsonNode root) {
        if (messageDelta) { throw protocol(); }
        int index = index(root);
        if (blocks.containsKey(Integer.valueOf(index))) { throw protocol(); }
        if (blocks.size() >= maxContentBlocks) resourceLimit();
        JsonNode content = root.get("content_block");
        if (content == null || !content.isObject()) { throw protocol(); }
        String type = requiredText(content, "type");
        Block block = new Block(type);
        if ("text".equals(type)) {
            String initial = optionalText(content, "text");
            if (initial != null) append(block.value, initial);
            blocks.put(Integer.valueOf(index), block);
            return new AnthropicStreamChunk(initial, null, null, null, null, null);
        }
        if (!"tool_use".equals(type)) { throw protocol(); }
        block.id = requiredText(content, "id");
        block.name = requiredText(content, "name");
        appendCount(block.id.length() + block.name.length());
        JsonNode input = content.get("input");
        if (input == null || !input.isObject() || input.size() != 0) { throw protocol(); }
        blocks.put(Integer.valueOf(index), block);
        return AnthropicStreamChunk.empty();
    }

    private AnthropicStreamChunk blockDelta(JsonNode root) {
        Block block = activeBlock(index(root));
        JsonNode delta = root.get("delta");
        if (delta == null || !delta.isObject()) { throw protocol(); }
        String type = requiredText(delta, "type");
        if ("text".equals(block.type) && "text_delta".equals(type)) {
            String value = requiredText(delta, "text");
            append(block.value, value);
            return new AnthropicStreamChunk(value, null, null, null, null, null);
        }
        if ("tool_use".equals(block.type) && "input_json_delta".equals(type)) {
            String value = requiredText(delta, "partial_json");
            append(block.value, value);
            return new AnthropicStreamChunk(null, block.id, block.name, value, null, null);
        }
        throw protocol();
    }

    private AnthropicStreamChunk blockStop(JsonNode root) {
        Block block = activeBlock(index(root));
        block.stopped = true;
        if (!"tool_use".equals(block.type)) return AnthropicStreamChunk.empty();
        try {
            JsonNode arguments = mapper.readTree(block.value.toString());
            if (arguments == null || !arguments.isObject()) { throw protocol(); }
        } catch (IOException exception) { throw protocol(); }
        ToolCallContent tool = new ToolCallContent(block.id, block.name, block.value.toString());
        return new AnthropicStreamChunk(null, block.id, block.name, null, tool, null);
    }

    private AnthropicStreamChunk messageDelta(JsonNode root) {
        if (messageDelta || !allBlocksStopped()) { throw protocol(); }
        JsonNode delta = root.get("delta");
        if (delta == null || !delta.isObject()) { throw protocol(); }
        finishReason = finish(requiredText(delta, "stop_reason"));
        stopSequence = optionalText(delta, "stop_sequence");
        JsonNode usage = root.get("usage");
        if (usage == null || !usage.isObject()) { throw protocol(); }
        outputTokens = nonNegativeInt(usage, "output_tokens");
        messageDelta = true;
        return AnthropicStreamChunk.empty();
    }

    private AnthropicStreamChunk messageStop() {
        if (!messageDelta || inputTokens == null || outputTokens == null) { throw protocol(); }
        List<Content> contents = new ArrayList<Content>();
        for (Block block : blocks.values()) {
            if ("text".equals(block.type)) contents.add(new TextContent(block.value.toString()));
            else contents.add(new ToolCallContent(block.id, block.name, block.value.toString()));
        }
        if (contents.isEmpty()) { throw protocol(); }
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        put(metadata, "id", id);
        put(metadata, "model", model);
        put(metadata, "stopSequence", stopSequence);
        metadata.put("provider", ModelProvider.ANTHROPIC.name());
        metadata.put("apiProfile", config.getApiProfile());
        ChatResponse response = new ChatResponse(new ChatMessage(Role.ASSISTANT, contents,
                null, null), new TokenUsage(inputTokens.intValue(), outputTokens.intValue()),
                finishReason, metadata);
        complete = true;
        return new AnthropicStreamChunk(null, null, null, null, null, response);
    }

    private Block activeBlock(int index) {
        Block block = blocks.get(Integer.valueOf(index));
        if (block == null || block.stopped) { throw protocol(); }
        return block;
    }

    private boolean allBlocksStopped() {
        if (blocks.isEmpty()) return false;
        for (Block block : blocks.values()) if (!block.stopped) return false;
        return true;
    }

    private void requireStarted() {
        if (!started || complete) { throw protocol(); }
    }

    private void append(StringBuilder target, String value) {
        appendCount(value.length());
        target.append(value);
    }

    private void appendCount(int count) {
        if (accumulatedChars > maxAccumulatedChars - count) resourceLimit();
        accumulatedChars += count;
    }

    private static int index(JsonNode root) {
        JsonNode value = root.get("index");
        if (value == null || !value.canConvertToInt() || value.intValue() < 0) { throw protocol(); }
        return value.intValue();
    }

    private static Integer nonNegativeInt(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.canConvertToInt() || value.intValue() < 0) { throw protocol(); }
        return Integer.valueOf(value.intValue());
    }

    private static String requiredText(JsonNode root, String field) {
        String value = optionalText(root, field);
        if (value == null) { throw protocol(); }
        return value;
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) { throw protocol(); }
        return value.textValue();
    }

    private static FinishReason finish(String reason) {
        if ("end_turn".equals(reason) || "stop_sequence".equals(reason)) return FinishReason.STOP;
        if ("max_tokens".equals(reason)) return FinishReason.LENGTH;
        if ("tool_use".equals(reason)) return FinishReason.TOOL_CALLS;
        if ("refusal".equals(reason)) return FinishReason.CONTENT_FILTER;
        return FinishReason.ERROR;
    }

    private static ClaraException providerError(JsonNode error) {
        String type = error != null && error.isObject() ? optionalText(error, "type") : null;
        if (type != null && !type.matches("[a-z0-9_]{1,64}")) type = null;
        ErrorCategory category = "authentication_error".equals(type) ? ErrorCategory.AUTHENTICATION
                : "permission_error".equals(type) ? ErrorCategory.AUTHORIZATION
                : "rate_limit_error".equals(type) ? ErrorCategory.RATE_LIMIT
                : ErrorCategory.EXTERNAL_SERVICE;
        boolean retryable = "rate_limit_error".equals(type) || "overloaded_error".equals(type);
        return error(ErrorCodes.MODEL_PROVIDER_FAILED, category, retryable, type);
    }

    private static void resourceLimit() {
        throw error(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, ErrorCategory.RESOURCE_LIMIT, false, null);
    }

    private static ClaraException protocol() {
        return error(ErrorCodes.MODEL_PROTOCOL_ERROR, ErrorCategory.EXTERNAL_SERVICE, false, null);
    }

    private static ClaraException error(String code, ErrorCategory category, boolean retryable,
            String providerCode) {
        return new ClaraException(new ClaraError(code, category, code, retryable, null,
                ModelProvider.ANTHROPIC.name(), providerCode, null,
                Collections.<String, String>emptyMap()));
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null) target.put(key, value);
    }

    /**
     * 表示。
     */
    private static final class Block {
        private final String type;
        private final StringBuilder value = new StringBuilder();
        private String id;
        private String name;
        private boolean stopped;
        private Block(String type) { this.type = type; }
    }
}
