package com.clara.model.provider.compatible;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.Content;
import com.clara.model.api.FinishReason;
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
 * 累积OpenAI兼容流的增量状态。
 */
final class OpenAiCompatibleStreamAccumulator {
    private final OpenAiCompatibleChatConfig config;
    private final int maxToolCalls;
    private final int maxAccumulatedChars;
    private final ObjectMapper mapper = new ObjectMapper();
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<Integer, ToolState> tools = new TreeMap<Integer, ToolState>();
    private String id;
    private String model;
    private String fingerprint;
    private FinishReason finishReason;
    private TokenUsage usage;
    private int accumulatedChars;
    private boolean sawText;
    private boolean complete;

    OpenAiCompatibleStreamAccumulator(OpenAiCompatibleChatConfig config,
            int maxToolCalls, int maxAccumulatedChars) {
        if (config == null) { throw new IllegalArgumentException("config must not be null"); }
        if (maxToolCalls <= 0 || maxAccumulatedChars <= 0) {
            throw new IllegalArgumentException("stream limits must be positive");
        }
        this.config = config;
        this.maxToolCalls = maxToolCalls;
        this.maxAccumulatedChars = maxAccumulatedChars;
    }

    OpenAiCompatibleStreamChunk accept(String data) {
        if (data == null) { throw new IllegalArgumentException("data must not be null"); }
        if (complete) { throw protocol(); }
        if ("[DONE]".equals(data.trim())) return complete();
        try {
            JsonNode root = mapper.readTree(data);
            if (root == null || !root.isObject()) { throw protocol(); }
            id = stable(id, textual(root.get("id")));
            model = stable(model, textual(root.get("model")));
            fingerprint = stable(fingerprint, textual(root.get("system_fingerprint")));
            TokenUsage chunkUsage = usage(root.get("usage"));
            if (chunkUsage != null) usage = stableUsage(usage, chunkUsage);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() > 1) { throw protocol(); }
            if (choices.size() == 0) {
                if (chunkUsage == null) { throw protocol(); }
                return emptyChunk();
            }
            JsonNode choice = choices.get(0);
            JsonNode index = choice.get("index");
            if (index != null && (!index.canConvertToInt() || index.intValue() != 0)) { throw protocol(); }
            JsonNode delta = choice.get("delta");
            if (delta == null || !delta.isObject()) { throw protocol(); }
            String role = textual(delta.get("role"));
            if (role != null && !"assistant".equals(role)) { throw protocol(); }
            String textDelta = optionalText(delta, "content");
            if (textDelta != null) {
                append(text, textDelta);
                sawText = true;
            }
            String reasoningDelta = optionalText(delta, "reasoning_content");
            if (reasoningDelta != null) append(reasoning, reasoningDelta);
            List<OpenAiCompatibleStreamChunk.ToolDelta> toolDeltas = tools(delta.get("tool_calls"));
            String finish = textual(choice.get("finish_reason"));
            if (finish != null) {
                FinishReason mapped = finish(finish);
                if (finishReason != null && finishReason != mapped) { throw protocol(); }
                finishReason = mapped;
            } else if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                throw protocol();
            }
            return new OpenAiCompatibleStreamChunk(textDelta, reasoningDelta, toolDeltas, null);
        } catch (ClaraException exception) {
            throw exception;
        } catch (IOException exception) {
            throw protocol();
        } catch (RuntimeException exception) {
            throw protocol();
        }
    }

    boolean isComplete() { return complete; }

    private OpenAiCompatibleStreamChunk complete() {
        if (finishReason == null) { throw protocol(); }
        List<Content> contents = new ArrayList<Content>();
        if (sawText) contents.add(new TextContent(text.toString()));
        for (ToolState tool : tools.values()) {
            if (blank(tool.id) || blank(tool.name)) { throw protocol(); }
            contents.add(new ToolCallContent(tool.id.toString(), tool.name.toString(),
                    tool.arguments.toString()));
        }
        if (contents.isEmpty()) { throw protocol(); }
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        put(metadata, "id", id);
        put(metadata, "model", model);
        put(metadata, "systemFingerprint", fingerprint);
        if (reasoning.length() > 0) metadata.put("reasoning", reasoning.toString());
        metadata.put("provider", config.getProvider().name());
        metadata.put("apiProfile", config.getApiProfile());
        ChatResponse response = new ChatResponse(new ChatMessage(Role.ASSISTANT, contents,
                null, null), usage, finishReason, metadata);
        complete = true;
        return new OpenAiCompatibleStreamChunk(null, null,
                Collections.<OpenAiCompatibleStreamChunk.ToolDelta>emptyList(), response);
    }

    private List<OpenAiCompatibleStreamChunk.ToolDelta> tools(JsonNode calls) {
        if (calls == null || calls.isNull()) return Collections.emptyList();
        if (!calls.isArray()) { throw protocol(); }
        List<OpenAiCompatibleStreamChunk.ToolDelta> result =
                new ArrayList<OpenAiCompatibleStreamChunk.ToolDelta>();
        for (JsonNode call : calls) {
            JsonNode index = call.get("index");
            if (index == null || !index.canConvertToInt() || index.intValue() < 0) { throw protocol(); }
            int value = index.intValue();
            ToolState state = tools.get(Integer.valueOf(value));
            if (state == null) {
                if (tools.size() >= maxToolCalls) resourceLimit();
                state = new ToolState();
                tools.put(Integer.valueOf(value), state);
            }
            String type = textual(call.get("type"));
            if (type != null && !"function".equals(type)) { throw protocol(); }
            String idDelta = optionalText(call, "id");
            JsonNode function = call.get("function");
            if (function != null && !function.isNull() && !function.isObject()) { throw protocol(); }
            String nameDelta = function == null || function.isNull()
                    ? null : optionalText(function, "name");
            String argumentsDelta = function == null || function.isNull()
                    ? null : optionalText(function, "arguments");
            if (idDelta == null && nameDelta == null && argumentsDelta == null) { throw protocol(); }
            if (idDelta != null) append(state.id, idDelta);
            if (nameDelta != null) append(state.name, nameDelta);
            if (argumentsDelta != null) append(state.arguments, argumentsDelta);
            result.add(new OpenAiCompatibleStreamChunk.ToolDelta(value,
                    emptyToNull(state.id), emptyToNull(state.name), argumentsDelta));
        }
        return result;
    }

    private void append(StringBuilder target, String value) {
        if (accumulatedChars > maxAccumulatedChars - value.length()) resourceLimit();
        target.append(value);
        accumulatedChars += value.length();
    }

    private static TokenUsage usage(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isObject()) { throw protocol(); }
        JsonNode input = value.get("prompt_tokens");
        JsonNode output = value.get("completion_tokens");
        if (input == null || output == null || !input.canConvertToInt() || !output.canConvertToInt()
                || input.intValue() < 0 || output.intValue() < 0) { throw protocol(); }
        return new TokenUsage(input.intValue(), output.intValue());
    }

    private static TokenUsage stableUsage(TokenUsage current, TokenUsage next) {
        if (current != null && (current.getInputTokens() != next.getInputTokens()
                || current.getOutputTokens() != next.getOutputTokens())) { throw protocol(); }
        return next;
    }

    private static FinishReason finish(String value) {
        if ("stop".equals(value)) return FinishReason.STOP;
        if ("length".equals(value)) return FinishReason.LENGTH;
        if ("tool_calls".equals(value) || "function_call".equals(value))
            return FinishReason.TOOL_CALLS;
        if ("content_filter".equals(value)) return FinishReason.CONTENT_FILTER;
        return FinishReason.ERROR;
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) { throw protocol(); }
        return value.textValue();
    }

    private static String textual(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) { throw protocol(); }
        return value.textValue();
    }

    private static String stable(String current, String next) {
        if (next == null) return current;
        if (current != null && !current.equals(next)) { throw protocol(); }
        return next;
    }

    private static String emptyToNull(StringBuilder value) {
        return value.length() == 0 ? null : value.toString();
    }

    private static boolean blank(StringBuilder value) {
        return value.length() == 0 || value.toString().trim().isEmpty();
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null) target.put(key, value);
    }

    private static OpenAiCompatibleStreamChunk emptyChunk() {
        return new OpenAiCompatibleStreamChunk(null, null,
                Collections.<OpenAiCompatibleStreamChunk.ToolDelta>emptyList(), null);
    }

    private static void resourceLimit() {
        throw error(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, ErrorCategory.RESOURCE_LIMIT);
    }

    private static ClaraException protocol() {
        return error(ErrorCodes.MODEL_PROTOCOL_ERROR, ErrorCategory.EXTERNAL_SERVICE);
    }

    private static ClaraException error(String code, ErrorCategory category) {
        return new ClaraException(new ClaraError(code, category, code, false, null,
                null, null, null, Collections.<String, String>emptyMap()));
    }

    /**
     * 表示。
     */
    private static final class ToolState {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
