package com.clara.model.provider.anthropic;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.Content;
import com.clara.model.api.FinishReason;
import com.clara.model.api.MediaContent;
import com.clara.model.api.ModelParameters;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.Role;
import com.clara.model.api.TextContent;
import com.clara.model.api.TokenUsage;
import com.clara.model.api.ToolCallContent;
import com.clara.tool.ToolSpecification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责Anthropic对话数据的编码与解码。
 */
final class AnthropicChatCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    byte[] encode(ChatRequest request, AnthropicChatConfig config) {
        return encode(request, config, false);
    }

    byte[] encode(ChatRequest request, AnthropicChatConfig config, boolean stream) {
        validateOptions(request);
        ModelParameters parameters = request.getParameters();
        if (parameters != null && parameters.getModel() != null
                && !config.getModelId().equals(parameters.getModel())) {
            throw new IllegalArgumentException("request model differs from registered model");
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("model", config.getModelId());
        root.put("max_tokens", parameters != null && parameters.getMaxTokens() != null
                ? parameters.getMaxTokens() : Integer.valueOf(config.getDefaultMaxTokens()));
        root.put("stream", Boolean.valueOf(stream));
        List<Map<String, Object>> system = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        boolean conversationStarted = false;
        for (ChatMessage message : request.getMessages()) {
            if (message.getRole() == Role.SYSTEM) {
                if (conversationStarted) { throw new IllegalArgumentException("system messages must be leading"); }
                system.addAll(textBlocks(message));
            } else {
                conversationStarted = true;
                messages.add(message(message));
            }
        }
        if (messages.isEmpty()) { throw new IllegalArgumentException("conversation requires a non-system message"); }
        if (!system.isEmpty()) root.put("system", system);
        root.put("messages", messages);
        parameters(root, parameters);
        if (!request.getTools().isEmpty()) root.put("tools", tools(request.getTools()));
        try { return mapper.writeValueAsBytes(root); }
        catch (JsonProcessingException exception) { throw protocol(); }
    }

    ChatResponse decode(byte[] body, AnthropicChatConfig config) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject() || !"assistant".equals(text(root.get("role")))) {
                throw protocol();
            }
            JsonNode blocks = root.get("content");
            if (blocks == null || !blocks.isArray() || blocks.size() == 0) { throw protocol(); }
            List<Content> contents = new ArrayList<Content>();
            for (JsonNode block : blocks) {
                String type = text(block.get("type"));
                if ("text".equals(type)) {
                    String value = text(block.get("text"));
                    if (value == null) { throw protocol(); }
                    contents.add(new TextContent(value));
                } else if ("tool_use".equals(type)) {
                    String id = text(block.get("id"));
                    String name = text(block.get("name"));
                    JsonNode input = block.get("input");
                    if (id == null || name == null || input == null || !input.isObject()) { throw protocol(); }
                    contents.add(new ToolCallContent(id, name, mapper.writeValueAsString(input)));
                } else {
                    throw protocol();
                }
            }
            TokenUsage usage = usage(root.get("usage"));
            Map<String, String> metadata = new LinkedHashMap<String, String>();
            put(metadata, "id", root.get("id"));
            put(metadata, "model", root.get("model"));
            put(metadata, "stopSequence", root.get("stop_sequence"));
            metadata.put("provider", ModelProvider.ANTHROPIC.name());
            metadata.put("apiProfile", config.getApiProfile());
            return new ChatResponse(new ChatMessage(Role.ASSISTANT, contents, null, null), usage,
                    finish(text(root.get("stop_reason"))), metadata);
        } catch (ClaraException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw protocol();
        } catch (java.io.IOException exception) {
            throw protocol();
        }
    }

    private Map<String, Object> message(ChatMessage message) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        if (message.getRole() == Role.TOOL) {
            if (message.getName() == null || message.getName().trim().isEmpty()) {
                throw new IllegalArgumentException("tool message requires tool use id as name");
            }
            value.put("role", "user");
            List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("type", "tool_result");
            result.put("tool_use_id", message.getName());
            result.put("content", joinedText(message));
            blocks.add(result);
            value.put("content", blocks);
            return value;
        }
        if (message.getRole() != Role.USER && message.getRole() != Role.ASSISTANT) {
            throw new IllegalArgumentException("unsupported Anthropic message role");
        }
        value.put("role", message.getRole() == Role.USER ? "user" : "assistant");
        List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
        for (Content content : message.getContents()) {
            if (content instanceof TextContent) blocks.add(textBlock(((TextContent) content).getText()));
            else if (content instanceof ToolCallContent && message.getRole() == Role.ASSISTANT)
                blocks.add(toolUse((ToolCallContent) content));
            else if (content instanceof MediaContent) capability();
            else throw new IllegalArgumentException("content is not valid for Anthropic Messages");
        }
        value.put("content", blocks);
        return value;
    }

    private List<Map<String, Object>> textBlocks(ChatMessage message) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Content content : message.getContents()) {
            if (!(content instanceof TextContent)) {
                if (content instanceof MediaContent) capability();
                throw new IllegalArgumentException("system content must be text");
            }
            result.add(textBlock(((TextContent) content).getText()));
        }
        return result;
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private Map<String, Object> toolUse(ToolCallContent call) {
        try {
            JsonNode input = mapper.readTree(call.getArguments());
            if (input == null || !input.isObject()) {
                throw new IllegalArgumentException("tool arguments must be a JSON object");
            }
            Map<String, Object> block = new LinkedHashMap<String, Object>();
            block.put("type", "tool_use");
            block.put("id", call.getId());
            block.put("name", call.getName());
            block.put("input", input);
            return block;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("tool arguments must be valid JSON", exception);
        }
    }

    private static String joinedText(ChatMessage message) {
        StringBuilder result = new StringBuilder();
        for (Content content : message.getContents()) {
            if (!(content instanceof TextContent)) {
                throw new IllegalArgumentException("tool result content must be text");
            }
            result.append(((TextContent) content).getText());
        }
        return result.toString();
    }

    private static List<Map<String, Object>> tools(List<ToolSpecification> tools) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ToolSpecification tool : tools) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("name", tool.getName());
            if (tool.getDescription() != null) value.put("description", tool.getDescription());
            value.put("input_schema", tool.getInputSchema());
            result.add(value);
        }
        return result;
    }

    private static void parameters(Map<String, Object> root, ModelParameters parameters) {
        if (parameters == null) return;
        put(root, "temperature", parameters.getTemperature());
        put(root, "top_p", parameters.getTopP());
        put(root, "top_k", parameters.getTopK());
        if (!parameters.getStops().isEmpty()) root.put("stop_sequences", parameters.getStops());
        if (parameters.getFrequencyPenalty() != null || parameters.getPresencePenalty() != null
                || parameters.getResponseFormat() != null) capability();
    }

    private static TokenUsage usage(JsonNode usage) {
        if (usage == null || usage.isNull()) return null;
        JsonNode input = usage.get("input_tokens");
        JsonNode output = usage.get("output_tokens");
        if (input == null || output == null || !input.canConvertToInt() || !output.canConvertToInt()
                || input.intValue() < 0 || output.intValue() < 0) { throw protocol(); }
        return new TokenUsage(input.intValue(), output.intValue());
    }

    private static FinishReason finish(String reason) {
        if ("end_turn".equals(reason) || "stop_sequence".equals(reason)) return FinishReason.STOP;
        if ("max_tokens".equals(reason)) return FinishReason.LENGTH;
        if ("tool_use".equals(reason)) return FinishReason.TOOL_CALLS;
        if ("refusal".equals(reason)) return FinishReason.CONTENT_FILTER;
        return FinishReason.ERROR;
    }

    private static void validateOptions(ChatRequest request) {
        if (request.getProviderOptions() == null) return;
        if (request.getProviderOptions().getProvider() != ModelProvider.ANTHROPIC) {
            throw new IllegalArgumentException("provider options do not match ANTHROPIC");
        }
        throw new IllegalArgumentException("Anthropic provider options are not enabled for this profile");
    }
    private static String text(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : null;
    }
    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
    private static void put(Map<String, String> target, String key, JsonNode value) {
        String text = text(value);
        if (text != null) target.put(key, text);
    }
    private static void capability() {
        throw error(ErrorCodes.CAPABILITY_NOT_SUPPORTED, ErrorCategory.CAPABILITY);
    }
    private static ClaraException protocol() {
        return error(ErrorCodes.MODEL_PROTOCOL_ERROR, ErrorCategory.EXTERNAL_SERVICE);
    }
    private static ClaraException error(String code, ErrorCategory category) {
        return new ClaraException(new ClaraError(code, category, code, false, null,
                ModelProvider.ANTHROPIC.name(), null, null,
                Collections.<String, String>emptyMap()));
    }
}
