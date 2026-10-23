package com.clara.kernel.message.provider.messages;

import com.clara.context.ConvContext;
import com.clara.core.annotation.ProtocolField;
import com.clara.core.message.IContent;
import com.clara.core.model.IRequest;
import com.clara.dto.tool.ToolContext;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.ProtocolEnum;
import com.clara.kernel.config.ProviderCapabilityConfiguration;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.message.BasicParameters;
import com.clara.kernel.message.provider.AbstractRequestEncoder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages 协议的请求编码器。
 *
 * <p>与 Chat Completions 的差异集中在三处：
 * <ul>
 *   <li>系统指令必须提升为顶层 {@code system}，不能留在 {@code messages} 里；</li>
 *   <li>{@code max_tokens} 是<b>必填</b>字段，请求未给出时取能力配置的 {@code parameters.maxTokens}，
 *       再缺省为 {@value #FALLBACK_MAX_TOKENS}；</li>
 *   <li>工具声明没有 {@code type=function} 外层，JSON Schema 键名是 {@code input_schema}；
 *       工具历史用 {@code tool_use} / {@code tool_result} 内容块表达。</li>
 * </ul>
 *
 * <p>其余字段（思考模式、惩罚项、日志概率等）在 {@link BasicParameters} 上以
 * {@code @ProviderAdapter(supported = false)} 声明，不会下发给平台。
 */
public final class MessagesRequestEncoder extends AbstractRequestEncoder {

    /**
     * 能力参数中输出上限的键名。
     */
    private static final String MAX_TOKENS_PARAMETER = "maxTokens";

    /**
     * 未配置输出上限时的兜底值：取 Claude 3 时代公开的最小输出上限，保证请求合法。
     */
    private static final int FALLBACK_MAX_TOKENS = 4096;

    /**
     * 解析 {@code tool_choice} 对象形式时使用的 JSON 映射器。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected String protocolName(Field field, String baseName) {
        for (ProtocolField protocolField : field.getDeclaredAnnotationsByType(ProtocolField.class)) {
            if (protocolField.protocol() == ProtocolEnum.MESSAGES) {
                if (!protocolField.supported()) {
                    return null;
                }
                return protocolField.name().isEmpty() ? baseName : protocolField.name();
            }
        }
        return baseName;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Object protocolValue(Field field, Object value) {
        if (!(value instanceof List)) {
            return value;
        }
        List<?> list = (List<?>) value;
        if (list.isEmpty()) {
            return value;
        }
        Object first = list.get(0);
        if (first instanceof IContent) {
            return convertMessages((List<IContent>) list);
        }
        if (first instanceof ToolContext) {
            return convertTools((List<ToolContext>) list);
        }
        return value;
    }

    @Override
    protected void postProcessBody(Map<String, Object> body, IRequest request, ModelProviderEnum provider,
            Map<String, String> authorization) {
        if (request instanceof BasicParameters) {
            applySystemInstruction(body, (BasicParameters) request);
        }
        ensureMaxTokens(body);
        applyToolChoice(body, request);
    }

    /**
     * 把系统消息提升为顶层 {@code system}。
     */
    private static void applySystemInstruction(Map<String, Object> body, BasicParameters request) {
        List<IContent> messages = request.getMessages();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<String> instructions = new ArrayList<>();
        for (IContent message : messages) {
            if (message.isSystemInstruction()) {
                String text = message.instructionText();
                if (text != null && !text.trim().isEmpty()) {
                    instructions.add(text);
                }
            }
        }
        if (!instructions.isEmpty()) {
            body.put("system", String.join("\n\n", instructions));
        }
    }

    /**
     * 补齐必填的 {@code max_tokens}。
     */
    private static void ensureMaxTokens(Map<String, Object> body) {
        Object current = body.get("max_tokens");
        if (current instanceof Number) {
            return;
        }
        Integer configured = configuredMaxTokens();
        body.put("max_tokens", configured == null ? FALLBACK_MAX_TOKENS : configured);
    }

    /**
     * 读取能力配置的 {@code parameters.maxTokens}。
     */
    private static Integer configuredMaxTokens() {
        ConvContext context = ConvContext.current();
        if (context == null || context.getProviderConfiguration() == null || context.getCapabilityKind() == null) {
            return null;
        }
        try {
            ProviderConfiguration provider = context.getProviderConfiguration();
            ProviderCapabilityConfiguration capability = provider.getCapability(context.getCapabilityKind());
            Object value = capability.getParameters().get(MAX_TOKENS_PARAMETER);
            if (!(value instanceof Number)) {
                return null;
            }
            int tokens = ((Number) value).intValue();
            return tokens > 0 ? tokens : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 把 Clara 的工具选择语义转换为 Messages API 的对象形式。
     */
    private static void applyToolChoice(Map<String, Object> body, IRequest request) {
        if (!(request instanceof BasicParameters)) {
            return;
        }
        BasicParameters parameters = (BasicParameters) request;
        Map<String, Object> toolChoice = toolChoiceOf(parameters.getToolChoice());
        if (Boolean.FALSE.equals(parameters.getParallelToolCalls())) {
            if (toolChoice.isEmpty()) {
                toolChoice.put("type", "auto");
            }
            toolChoice.put("disable_parallel_tool_use", Boolean.TRUE);
        }
        if (!toolChoice.isEmpty()) {
            body.put("tool_choice", toolChoice);
        }
    }

    private static Map<String, Object> toolChoiceOf(String choice) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (choice == null || choice.trim().isEmpty()) {
            return result;
        }
        String value = choice.trim();
        if ("none".equals(value)) {
            result.put("type", "none");
        } else if ("auto".equals(value)) {
            result.put("type", "auto");
        } else if ("required".equals(value) || "any".equals(value)) {
            result.put("type", "any");
        } else if (value.startsWith("{")) {
            applyToolChoiceObject(result, value);
        }
        return result;
    }

    private static void applyToolChoiceObject(Map<String, Object> result, String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode function = root.get("function");
            JsonNode name = function == null ? null : function.get("name");
            if (name != null && name.isTextual()) {
                result.put("type", "tool");
                result.put("name", name.textValue());
                return;
            }
            JsonNode type = root.get("type");
            if (type != null && type.isTextual()) {
                result.put("type", type.textValue());
            }
        } catch (JsonProcessingException ignored) {
            // 非法对象形式不下发，交给平台按默认策略处理。
        }
    }

    private static List<Map<String, Object>> convertMessages(List<IContent> contents) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (IContent content : contents) {
            if (content.isSystemInstruction()) {
                continue;
            }
            Map<String, Object> raw = content.toCompletionMessage();
            String role = text(raw.get("role"));
            if ("tool".equals(role)) {
                appendToolResult(messages, raw);
            } else if ("assistant".equals(role)) {
                Map<String, Object> message = assistantMessage(raw);
                if (message != null) {
                    messages.add(message);
                }
            } else {
                messages.add(userMessage(raw));
            }
        }
        return messages;
    }

    private static Map<String, Object> userMessage(Map<String, Object> raw) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", userContent(raw.get("content")));
        return message;
    }

    private static Object userContent(Object content) {
        if (!(content instanceof List)) {
            return content;
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Object part : (List<?>) content) {
            if (!(part instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) part;
            String type = text(item.get("type"));
            if ("text".equals(type)) {
                blocks.add(textBlock(text(item.get("text"))));
            } else if ("image_url".equals(type)) {
                Map<String, Object> block = imageBlock(item.get("image_url"));
                if (block != null) {
                    blocks.add(block);
                }
            }
        }
        return blocks;
    }

    private static Map<String, Object> imageBlock(Object imageUrl) {
        if (!(imageUrl instanceof Map)) {
            return null;
        }
        String url = text(((Map<?, ?>) imageUrl).get("url"));
        if (url == null || url.isEmpty()) {
            return null;
        }
        Map<String, Object> source = new LinkedHashMap<>();
        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            int semicolon = url.indexOf(';');
            if (comma < 0 || semicolon < 0) {
                return null;
            }
            source.put("type", "base64");
            source.put("media_type", url.substring("data:".length(), semicolon));
            source.put("data", url.substring(comma + 1));
        } else {
            source.put("type", "url");
            source.put("url", url);
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("source", source);
        return block;
    }

    private static Map<String, Object> assistantMessage(Map<String, Object> raw) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        String text = text(raw.get("content"));
        if (text != null && !text.isEmpty()) {
            blocks.add(textBlock(text));
        }
        Object calls = raw.get("tool_calls");
        if (calls instanceof List) {
            for (Object call : (List<?>) calls) {
                if (call instanceof Map) {
                    blocks.add(toolUseBlock(castMap(call)));
                }
            }
        }
        if (blocks.isEmpty()) {
            return null;
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", blocks);
        return message;
    }

    private static Map<String, Object> toolUseBlock(Map<String, Object> call) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_use");
        block.put("id", text(call.get("id")));
        Object function = call.get("function");
        Map<String, Object> fn = function instanceof Map ? castMap(function) : new LinkedHashMap<>();
        block.put("name", text(fn.get("name")));
        block.put("input", parseArguments(fn.get("arguments")));
        return block;
    }

    private static Object parseArguments(Object arguments) {
        if (!(arguments instanceof String) || ((String) arguments).trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            JsonNode node = MAPPER.readTree((String) arguments);
            return node != null && node.isObject() ? node : new LinkedHashMap<String, Object>();
        } catch (JsonProcessingException exception) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private static void appendToolResult(List<Map<String, Object>> messages, Map<String, Object> raw) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", text(raw.get("tool_call_id")));
        String content = text(raw.get("content"));
        block.put("content", content == null ? "" : content);

        if (!messages.isEmpty()) {
            Map<String, Object> last = messages.get(messages.size() - 1);
            Object lastContent = last.get("content");
            if ("user".equals(last.get("role")) && lastContent instanceof List) {
                addBlock(lastContent, block);
                return;
            }
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(block);
        message.put("content", blocks);
        messages.add(message);
    }

    @SuppressWarnings("unchecked")
    private static void addBlock(Object content, Map<String, Object> block) {
        ((List<Map<String, Object>>) content).add(block);
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text == null ? "" : text);
        return block;
    }

    private static List<Map<String, Object>> convertTools(List<ToolContext> tools) {
        List<Map<String, Object>> declarations = new ArrayList<>();
        for (ToolContext tool : tools) {
            Map<String, Object> declaration = tool.toMessagesTool();
            if (!declaration.isEmpty()) {
                declarations.add(declaration);
            }
        }
        return declarations;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
