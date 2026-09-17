package com.clara.model.provider.compatible;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.model.provider.deepseek.DeepSeekChatOptions;
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
import java.util.Locale;
import java.util.Map;

/**
 * 负责OpenAI兼容对话数据的编码与解码。
 */
final class OpenAiCompatibleChatCodec {
    private final ObjectMapper mapper;

    OpenAiCompatibleChatCodec() {
        this(new ObjectMapper());
    }

    OpenAiCompatibleChatCodec(ObjectMapper mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        this.mapper = mapper;
    }

    byte[] encode(ChatRequest request, OpenAiCompatibleChatConfig config) {
        return encode(request, config, false);
    }

    byte[] encode(ChatRequest request, OpenAiCompatibleChatConfig config, boolean stream) {
        if (request == null || config == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        validateOptions(request, config.getProvider());
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("model", configuredModel(request.getParameters(), config));
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        for (ChatMessage message : request.getMessages()) messages.add(message(message));
        applyProviderOptions(request, messages, config.getProvider());
        root.put("messages", messages);
        if (stream) root.put("stream", Boolean.TRUE);
        parameters(root, request.getParameters());
        if (!request.getTools().isEmpty()) root.put("tools", tools(request.getTools()));
        try {
            return mapper.writeValueAsBytes(root);
        } catch (JsonProcessingException exception) {
            throw protocol(config.getProvider());
        }
    }

    ChatResponse decode(byte[] body, OpenAiCompatibleChatConfig config) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode choices = root == null ? null : root.get("choices");
            if (root == null || !root.isObject() || choices == null || !choices.isArray()
                    || choices.size() == 0) {
                throw protocol(config.getProvider());
            }
            JsonNode choice = choices.get(0);
            JsonNode message = choice.get("message");
            if (message == null || !message.isObject()) {
                throw protocol(config.getProvider());
            }
            String role = text(message.get("role"));
            if (role != null && !"assistant".equals(role)) {
                throw protocol(config.getProvider());
            }
            List<Content> contents = responseContents(message, config.getProvider());
            ChatMessage resultMessage = new ChatMessage(Role.ASSISTANT, contents,
                    text(message.get("name")), Collections.<String, String>emptyMap());
            TokenUsage usage = usage(root.get("usage"), config.getProvider());
            FinishReason finish = finish(text(choice.get("finish_reason")));
            Map<String, String> metadata = new LinkedHashMap<String, String>();
            put(metadata, "id", root.get("id"));
            put(metadata, "model", root.get("model"));
            put(metadata, "systemFingerprint", root.get("system_fingerprint"));
            if (config.getProvider() == ModelProvider.DEEPSEEK) {
                JsonNode reasoning = message.get("reasoning_content");
                if (reasoning != null && !reasoning.isNull() && !reasoning.isTextual()) {
                    throw protocol(config.getProvider());
                }
                put(metadata, "reasoning", reasoning);
            }
            metadata.put("provider", config.getProvider().name());
            metadata.put("apiProfile", config.getApiProfile());
            return new ChatResponse(resultMessage, usage, finish, metadata);
        } catch (ClaraException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw protocol(config.getProvider());
        } catch (java.io.IOException exception) {
            throw protocol(config.getProvider());
        }
    }

    private static Map<String, Object> message(ChatMessage message) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("role", message.getRole().name().toLowerCase(Locale.ROOT));
        if (message.getName() != null) {
            value.put(message.getRole() == Role.TOOL ? "tool_call_id" : "name", message.getName());
        } else if (message.getRole() == Role.TOOL) {
            throw new IllegalArgumentException("tool message requires tool call id as name");
        }
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> calls = new ArrayList<Map<String, Object>>();
        for (Content content : message.getContents()) {
            if (content instanceof TextContent) text.append(((TextContent) content).getText());
            else if (content instanceof ToolCallContent && message.getRole() == Role.ASSISTANT)
                calls.add(toolCall((ToolCallContent) content));
            else if (content instanceof MediaContent) capability();
            else throw new IllegalArgumentException("content is not valid for OpenAI-compatible chat");
        }
        value.put("content", text.length() == 0 && !calls.isEmpty() ? null : text.toString());
        if (!calls.isEmpty()) value.put("tool_calls", calls);
        return value;
    }

    private static Map<String, Object> toolCall(ToolCallContent call) {
        Map<String, Object> function = new LinkedHashMap<String, Object>();
        function.put("name", call.getName());
        function.put("arguments", call.getArguments());
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("id", call.getId());
        value.put("type", "function");
        value.put("function", function);
        return value;
    }

    private static List<Map<String, Object>> tools(List<ToolSpecification> specifications) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ToolSpecification specification : specifications) {
            Map<String, Object> function = new LinkedHashMap<String, Object>();
            function.put("name", specification.getName());
            if (specification.getDescription() != null)
                function.put("description", specification.getDescription());
            function.put("parameters", specification.getInputSchema());
            Map<String, Object> tool = new LinkedHashMap<String, Object>();
            tool.put("type", "function");
            tool.put("function", function);
            result.add(tool);
        }
        return result;
    }

    private static void parameters(Map<String, Object> root, ModelParameters parameters) {
        if (parameters == null) return;
        if (parameters.getTopK() != null) capability();
        put(root, "temperature", parameters.getTemperature());
        put(root, "top_p", parameters.getTopP());
        put(root, "max_tokens", parameters.getMaxTokens());
        put(root, "frequency_penalty", parameters.getFrequencyPenalty());
        put(root, "presence_penalty", parameters.getPresencePenalty());
        if (!parameters.getStops().isEmpty()) root.put("stop", parameters.getStops());
        if (parameters.getResponseFormat() != null) {
            Map<String, Object> format = new LinkedHashMap<String, Object>();
            format.put("type", parameters.getResponseFormat());
            root.put("response_format", format);
        }
    }

    private static String configuredModel(ModelParameters parameters,
                                          OpenAiCompatibleChatConfig config) {
        if (parameters != null && parameters.getModel() != null
                && !config.getModelId().equals(parameters.getModel())) {
            throw new IllegalArgumentException("request model differs from registered model");
        }
        return config.getModelId();
    }

    private static void validateOptions(ChatRequest request, ModelProvider provider) {
        if (request.getProviderOptions() == null) return;
        if (request.getProviderOptions().getProvider() != provider) {
            throw new IllegalArgumentException("provider options do not match configured provider");
        }
        if (provider == ModelProvider.DEEPSEEK
                && request.getProviderOptions() instanceof DeepSeekChatOptions) return;
        throw new IllegalArgumentException("provider options type is not supported");
    }

    private static void applyProviderOptions(ChatRequest request,
                                             List<Map<String, Object>> messages, ModelProvider provider) {
        if (!(request.getProviderOptions() instanceof DeepSeekChatOptions)) return;
        if (provider != ModelProvider.DEEPSEEK) {
            throw new IllegalArgumentException("DeepSeek options require DEEPSEEK provider");
        }
        DeepSeekChatOptions options = (DeepSeekChatOptions) request.getProviderOptions();
        if (options.getPrefixCompletion() == null && options.getReasoningContent() == null) return;
        int last = request.getMessages().size() - 1;
        if (request.getMessages().get(last).getRole() != Role.ASSISTANT) {
            throw new IllegalArgumentException("DeepSeek prefix completion requires final assistant message");
        }
        Map<String, Object> target = messages.get(last);
        if (options.getPrefixCompletion() != null)
            target.put("prefix", options.getPrefixCompletion());
        if (options.getReasoningContent() != null)
            target.put("reasoning_content", options.getReasoningContent());
    }

    private static List<Content> responseContents(JsonNode message, ModelProvider provider) {
        List<Content> contents = new ArrayList<Content>();
        JsonNode content = message.get("content");
        if (content != null && !content.isNull()) {
            if (!content.isTextual()) {
                throw protocol(provider);
            }
            contents.add(new TextContent(content.textValue()));
        }
        JsonNode calls = message.get("tool_calls");
        if (calls != null && !calls.isNull()) {
            if (!calls.isArray()) {
                throw protocol(provider);
            }
            for (JsonNode call : calls) {
                JsonNode function = call.get("function");
                String id = text(call.get("id"));
                String name = function == null ? null : text(function.get("name"));
                String arguments = function == null ? null : text(function.get("arguments"));
                if (id == null || name == null || arguments == null) {
                    throw protocol(provider);
                }
                contents.add(new ToolCallContent(id, name, arguments));
            }
        }
        if (contents.isEmpty()) {
            throw protocol(provider);
        }
        return contents;
    }

    private static TokenUsage usage(JsonNode value, ModelProvider provider) {
        if (value == null || value.isNull()) return null;
        JsonNode input = value.get("prompt_tokens");
        JsonNode output = value.get("completion_tokens");
        if (input == null || output == null || !input.canConvertToInt() || !output.canConvertToInt()
                || input.intValue() < 0 || output.intValue() < 0) {
            throw protocol(provider);
        }
        return new TokenUsage(input.intValue(), output.intValue());
    }

    private static FinishReason finish(String value) {
        if ("stop".equals(value)) return FinishReason.STOP;
        if ("length".equals(value)) return FinishReason.LENGTH;
        if ("tool_calls".equals(value) || "function_call".equals(value)) return FinishReason.TOOL_CALLS;
        if ("content_filter".equals(value)) return FinishReason.CONTENT_FILTER;
        return FinishReason.ERROR;
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
        throw error(ErrorCodes.CAPABILITY_NOT_SUPPORTED, ErrorCategory.CAPABILITY, false, null);
    }

    private static ClaraException protocol(ModelProvider provider) {
        return error(ErrorCodes.MODEL_PROTOCOL_ERROR, ErrorCategory.EXTERNAL_SERVICE, false, provider);
    }

    private static ClaraException error(String code, ErrorCategory category, boolean retryable,
                                        ModelProvider provider) {
        return new ClaraException(new ClaraError(code, category, code, retryable, null,
                provider == null ? null : provider.name(), null, null,
                Collections.<String, String>emptyMap()));
    }
}
