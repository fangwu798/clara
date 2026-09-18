package com.clara.model.provider.compatible;

import com.clara.kernel.ClaraException;
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
import com.clara.model.provider.compatible.config.OpenAiCompatibleChatConfig;
import com.clara.model.provider.compatible.config.OpenAiCompatibleReasoning;
import com.clara.model.utils.ExceptionUtils;
import com.clara.model.utils.FinishReasonUtils;
import com.clara.model.utils.JsonNodeUtils;
import com.clara.model.utils.MapUtils;
import com.clara.tool.ToolSpecification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 负责 OpenAI 兼容对话请求与响应的编解码，并集中承载协议细节与校验规则。
 *
 * <p>职责边界（重要）：
 * <ul>
 *   <li>请求编码 {@link #encode(ChatRequest, OpenAiCompatibleChatConfig)} /
 *       {@link #encode(ChatRequest, OpenAiCompatibleChatConfig, boolean)}：同步与流式共用，
 *       流式只是额外置 {@code stream=true}；</li>
 *   <li>响应解码 {@link #decode(byte[], OpenAiCompatibleChatConfig)}：<b>只处理非流式响应</b>，
 *       即读取 {@code choices[0].message} 的完整返回；流式分块（{@code choices[0].delta} 增量）
 *       不经过本类，由 {@link OpenAiCompatibleStreamAccumulator} 累积后再产出 {@link ChatResponse}。</li>
 * </ul>
 *
 * <p>转换内容：在领域对象与 OpenAI 兼容 JSON 之间互转，是本包内唯一的协议知识出口：
 * <ul>
 *   <li>编码：按角色映射消息（{@code tool} 消息的 {@code name} 作为 {@code tool_call_id}）、拼接文本内容、
 *       转换工具声明与工具调用、映射采样参数，平台特有字段则交由平台选项实现
 *       {@link OpenAiCompatibleOptions} 自行写入；</li>
 *   <li>解码：校验 {@code choices}/{@code message}/{@code finish_reason}/{@code usage} 的结构，把文本与
 *       工具调用还原为领域内容，并把 {@code id}/{@code model}/{@code system_fingerprint} 及平台信息
 *       写入响应元数据。</li>
 * </ul>
 *
 * <p>平台字段归属：请求侧 DeepSeek 独有的前缀补全字段由平台选项通过 {@link OpenAiCompatibleOptions} 自行
 * 写入；响应侧的推理内容字段则是 OpenAI 兼容家族通用字段（DeepSeek、火山、阿里等同名返回），属于本协议
 * 的一部分，由本包统一解析，见 {@link OpenAiCompatibleReasoning}。
 *
 * <p>使用方式：{@link OpenAiCompatibleChatModel}（同步）用它完成 encode + decode；
 * {@link OpenAiCompatibleStreamingChatModel}（流式）只使用它的 encode，响应侧改用累加器。
 *
 * <p>注意：本类不做“尽力而为”的容错——出现媒体内容、{@code topK} 等平台不支持的输入会抛
 * {@code CAPABILITY_NOT_SUPPORTED}；平台选项与配置不匹配、请求模型与注册模型不一致、响应结构缺失或
 * 类型不符时抛 {@link IllegalArgumentException} 或 {@code MODEL_PROTOCOL_ERROR}，避免脏数据被静默接受。
 * 异常统一由 {@link ExceptionUtils} 构造，映射写入走 {@link MapUtils#putIfNotNull}，
 * 宽松的节点取值走 {@link JsonNodeUtils#textOrNull(JsonNode)}。
 */
final class OpenAiCompatibleChatCodec {
    /**
     * JSON 映射器，负责 JSON 与领域字段之间的转换。
     */
    private final ObjectMapper mapper;

    /**
     * 使用默认的 Jackson 映射器创建编解码器。
     */
    OpenAiCompatibleChatCodec() {
        this(new ObjectMapper());
    }

    /**
     * 使用指定映射器创建编解码器，便于测试注入。
     *
     * @param mapper JSON 映射器，不可为 null
     * @throws IllegalArgumentException mapper 为 null 时
     */
    OpenAiCompatibleChatCodec(ObjectMapper mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        this.mapper = mapper;
    }

    /**
     * 编码非流式对话请求。
     *
     * @param request 对话请求
     * @param config  对话配置
     * @return UTF-8 编码的请求体
     */
    byte[] encode(ChatRequest request, OpenAiCompatibleChatConfig config) {
        return encode(request, config, false);
    }

    /**
     * 编码对话请求，可选择是否置流式标记。
     *
     * <p>编码时按角色映射消息、转换工具声明与调用、映射采样参数；平台特有字段（如 DeepSeek 前缀补全的
     * {@code prefix} 与 {@code reasoning_content}）由平台选项实现 {@link OpenAiCompatibleOptions} 自行写入。
     *
     * <p>置流式标记时会一并写入 {@code stream_options.include_usage=true}：兼容家族多数平台（火山方舟、
     * OpenAI 等）默认不在流式响应里回传 {@code usage}，只有显式开启该开关后才会在收尾块补一段
     * {@code choices} 为空、仅带 {@code usage} 的数据，否则上游拿不到 token 用量。
     *
     * @param request 对话请求
     * @param config  对话配置
     * @param stream  是否在请求体中置 {@code stream=true} 并开启 {@code stream_options.include_usage}
     * @return UTF-8 编码的请求体
     * @throws IllegalArgumentException 请求或配置为 null、平台选项与配置不匹配、模型不一致、消息内容非法时
     * @throws ClaraException           含平台不支持的参数（如 topK、媒体内容）或序列化失败时
     */
    byte[] encode(ChatRequest request, OpenAiCompatibleChatConfig config, boolean stream) {
        if (request == null || config == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        validateOptions(request, config.getProvider());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", configuredModel(request.getParameters(), config));
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage message : request.getMessages()) {
            messages.add(message(message));
        }
        applyProviderOptions(request, messages);
        root.put("messages", messages);
        if (stream) {
            root.put("stream", Boolean.TRUE);
            root.put("stream_options", Collections.singletonMap("include_usage", Boolean.TRUE));
        }
        parameters(root, request.getParameters());

        if (!request.getTools().isEmpty()) {
            root.put("tools", tools(request.getTools()));
        }

        try {
            return mapper.writeValueAsBytes(root);
        } catch (JsonProcessingException exception) {
            throw ExceptionUtils.protocolError(config.getProvider());
        }
    }

    /**
     * 解码非流式对话响应。
     *
     * <p>只适用于 {@code stream=false} 的完整响应：以 {@code choices[0].message} 为内容来源，严格校验
     * {@code choices} 与 {@code message} 结构、角色与 {@code finish_reason}/{@code usage} 字段，把文本与
     * 工具调用还原为领域内容，并把 {@code id}/{@code model}/{@code system_fingerprint}、兼容家族的
     * {@code reasoning_content} 以及平台与协议档位写入响应元数据。
     *
     * <p>流式分块（形如 {@code choices[0].delta}）没有 {@code message} 字段，不可传入本方法，
     * 应由 {@link OpenAiCompatibleStreamAccumulator} 逐块累积后再产出完整响应。
     * 响应结果示例如下：
     * {
     * "id": "930c60df-bf64-41c9-a88e-3ec75f81e00e",
     * "choices": [
     * {
     * "finish_reason": "stop",
     * "index": 0,
     * "message": {
     * "content": "Hello! How can I help you today?",
     * "role": "assistant"
     * },
     * "logprobs": null
     * }
     * ],
     * "created": 1705651092,
     * "model": "deepseek-flash",
     * "object": "chat.completion",
     * "system_fingerprint": "fp_7a09fdf9c2",
     * "usage": {
     * "completion_tokens": 10,
     * "prompt_tokens": 16,
     * "total_tokens": 26,
     * "prompt_tokens_details": {
     * "cached_tokens": 0
     * },
     * "prompt_cache_hit_tokens": 0,
     * "prompt_cache_miss_tokens": 16
     * }
     * }
     *
     * @param body   响应体字节
     * @param config 对话配置
     * @return 领域对话响应
     * @throws ClaraException 响应结构不符合协议或存在平台特有字段异常时
     */
    ChatResponse decode(byte[] body, OpenAiCompatibleChatConfig config) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode choices = root == null ? null : root.get("choices");
            if (root == null || !root.isObject() || choices == null || !choices.isArray() || choices.isEmpty()) {
                throw ExceptionUtils.protocolError(config.getProvider());
            }
            JsonNode choice = choices.get(0);
            JsonNode message = choice.get("message");
            if (message == null || !message.isObject()) {
                throw ExceptionUtils.protocolError(config.getProvider());
            }
            String role = JsonNodeUtils.textOrNull(message.get("role"));
            if (role != null && !"assistant".equals(role)) {
                throw ExceptionUtils.protocolError(config.getProvider());
            }

            List<Content> contents = responseContents(message, config.getProvider());
            ChatMessage resultMessage = new ChatMessage(Role.ASSISTANT, contents, JsonNodeUtils.textOrNull(message.get("name")), Collections.emptyMap());
            TokenUsage usage = usage(root.get("usage"));
            FinishReason finish = FinishReasonUtils.of(JsonNodeUtils.textOrNull(choice.get("finish_reason")));

            Map<String, String> metadata = new LinkedHashMap<>();
            MapUtils.putIfNotNull(metadata, "id", JsonNodeUtils.textOrNull(root.get("id")));
            MapUtils.putIfNotNull(metadata, "model", JsonNodeUtils.textOrNull(root.get("model")));
            MapUtils.putIfNotNull(metadata, "systemFingerprint", JsonNodeUtils.textOrNull(root.get("system_fingerprint")));

            JsonNode reasoning = message.get(OpenAiCompatibleReasoning.CONTENT_FIELD);
            MapUtils.putIfNotNull(metadata, OpenAiCompatibleReasoning.METADATA_KEY, JsonNodeUtils.textOrNull(reasoning));

            metadata.put("provider", config.getProvider().name());
            metadata.put("apiProfile", config.getApiProfile());
            return new ChatResponse(resultMessage, usage, finish, metadata);
        } catch (ClaraException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw ExceptionUtils.protocolError(config.getProvider());
        }
    }

    /**
     * 把领域消息转换为 OpenAI 兼容的消息对象。
     *
     * <p>角色名小写；{@code tool} 消息用 name 承载 {@code tool_call_id}；文本内容拼接为 content，
     * 助手消息中的工具调用放入 {@code tool_calls}；仅有工具调用而无文本时 content 置 null。
     *
     * @param message 领域消息
     * @return OpenAI 兼容的消息对象
     * @throws IllegalArgumentException 工具消息缺少调用 id，或内容类型不被支持时
     * @throws ClaraException           出现媒体内容等平台不支持的能力时
     */
    private static Map<String, Object> message(ChatMessage message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("role", message.getRole().name().toLowerCase(Locale.ROOT));
        if (message.getName() != null) {
            value.put(message.getRole() == Role.TOOL ? "tool_call_id" : "name", message.getName());
        } else if (message.getRole() == Role.TOOL) {
            throw new IllegalArgumentException("tool message requires tool call id as name");
        }
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> calls = new ArrayList<>();
        for (Content content : message.getContents()) {
            if (content instanceof TextContent) {
                text.append(((TextContent) content).getText());
            } else if (content instanceof ToolCallContent && message.getRole() == Role.ASSISTANT) {
                calls.add(toolCall((ToolCallContent) content));
            } else if (content instanceof MediaContent) {
                throw ExceptionUtils.capabilityUnsupported();
            } else {
                throw new IllegalArgumentException("content is not valid for OpenAI-compatible chat");
            }
        }
        value.put("content", text.length() == 0 && !calls.isEmpty() ? null : text.toString());
        if (!calls.isEmpty()) {
            value.put("tool_calls", calls);
        }
        return value;
    }

    /**
     * 把领域工具调用转换为 OpenAI 兼容的 {@code tool_calls} 元素。
     *
     * @param call 领域工具调用
     * @return 工具调用对象
     */
    private static Map<String, Object> toolCall(ToolCallContent call) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", call.getName());
        function.put("arguments", call.getArguments());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", call.getId());
        value.put("type", "function");
        value.put("function", function);
        return value;
    }

    /**
     * 把工具声明转换为 OpenAI 兼容的 {@code tools} 数组。
     *
     * @param specifications 工具声明列表
     * @return {@code tools} 数组，描述为空时省略 description 字段
     */
    private static List<Map<String, Object>> tools(List<ToolSpecification> specifications) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolSpecification specification : specifications) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", specification.getName());
            if (specification.getDescription() != null) {
                function.put("description", specification.getDescription());
            }
            function.put("parameters", specification.getInputSchema());
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            result.add(tool);
        }
        return result;
    }

    /**
     * 把采样参数映射为 OpenAI 兼容字段。
     *
     * <p>为空的值不下发；{@code topK} 属于兼容协议不支持的参数，出现即抛能力错误。
     *
     * @param root       请求体根对象
     * @param parameters 请求参数，可为 null
     * @throws ClaraException 出现平台不支持的参数时
     */
    private static void parameters(Map<String, Object> root, ModelParameters parameters) {
        if (parameters == null) {
            return;
        }
        if (parameters.getTopK() != null) {
            throw ExceptionUtils.capabilityUnsupported();
        }
        MapUtils.putIfNotNull(root, "temperature", parameters.getTemperature());
        MapUtils.putIfNotNull(root, "top_p", parameters.getTopP());
        MapUtils.putIfNotNull(root, "max_tokens", parameters.getMaxTokens());
        MapUtils.putIfNotNull(root, "frequency_penalty", parameters.getFrequencyPenalty());
        MapUtils.putIfNotNull(root, "presence_penalty", parameters.getPresencePenalty());
        if (!parameters.getStops().isEmpty()) {
            root.put("stop", parameters.getStops());
        }
        if (parameters.getResponseFormat() != null) {
            Map<String, Object> format = new LinkedHashMap<>();
            format.put("type", parameters.getResponseFormat());
            root.put("response_format", format);
        }
    }

    /**
     * 解析请求体使用的模型 ID。
     *
     * <p>请求参数若指定了与注册模型不同的模型，直接拒绝，避免绕过平台配置调用未注册的模型。
     *
     * @param parameters 请求参数，可为 null
     * @param config     对话配置
     * @return 注册的模型 ID
     * @throws IllegalArgumentException 请求模型与注册模型不一致时
     */
    private static String configuredModel(ModelParameters parameters, OpenAiCompatibleChatConfig config) {
        if (parameters != null && parameters.getModel() != null && !config.getModelId().equals(parameters.getModel())) {
            throw new IllegalArgumentException("request model differs from registered model");
        }
        return config.getModelId();
    }

    /**
     * 校验请求携带的平台选项与配置平台匹配且类型受支持。
     *
     * <p>平台专属选项必须以 {@link OpenAiCompatibleOptions} 的形式提供扩展点，本类只认这个接口；
     * 携带了平台选项却未实现该接口的选项类型一律拒绝，避免选项被静默忽略。
     *
     * @param request  对话请求
     * @param provider 配置平台
     * @throws IllegalArgumentException 平台不匹配或选项类型不受支持时
     */
    private static void validateOptions(ChatRequest request, ModelProvider provider) {
        if (request.getProviderOptions() == null) {
            return;
        }
        if (request.getProviderOptions().getProvider() != provider) {
            throw new IllegalArgumentException("provider options do not match configured provider");
        }
        if (request.getProviderOptions() instanceof OpenAiCompatibleOptions) {
            return;
        }
        throw new IllegalArgumentException("provider options type is not supported");
    }

    /**
     * 应用平台特有的请求选项，是各平台扩展选项的统一入口。
     *
     * <p>本类不感知任何具体平台：选项类实现 {@link OpenAiCompatibleOptions} 后自行把平台字段写入
     * 已编码的消息，因此新增平台只需实现该接口，本类无需改动。
     *
     * <p>调用前提：选项平台与配置平台是否一致、选项类型是否受支持已由
     * {@link #validateOptions(ChatRequest, ModelProvider)} 校验。
     *
     * @param request  对话请求
     * @param messages 已编码的消息列表
     * @throws IllegalArgumentException 平台选项与请求上下文不匹配（如 DeepSeek 前缀补全的最后一条不是助手消息）时
     */
    private static void applyProviderOptions(ChatRequest request, List<Map<String, Object>> messages) {
        if (request.getProviderOptions() instanceof OpenAiCompatibleOptions) {
            ((OpenAiCompatibleOptions) request.getProviderOptions()).applyTo(request, messages);
        }
    }

    /**
     * 把响应消息解析为领域内容。
     *
     * <p>文本与工具调用至少要出现一项，否则按协议错误处理；各字段的校验细节见
     * {@link #textContent(JsonNode, ModelProvider)} 与 {@link #toolCallContents(JsonNode, ModelProvider)}。
     *
     * @param message  响应中的 message 节点
     * @param provider 平台，用于错误归因
     * @return 领域内容列表，文本内容在前、工具调用在后
     * @throws ClaraException 结构或字段类型非法，或没有任何可返回内容时
     */
    private static List<Content> responseContents(JsonNode message, ModelProvider provider) {
        List<Content> contents = new ArrayList<>();
        Content text = textContent(message, provider);
        if (text != null) {
            contents.add(text);
        }
        contents.addAll(toolCallContents(message, provider));
        if (contents.isEmpty()) {
            throw ExceptionUtils.protocolError(provider);
        }
        return contents;
    }

    /**
     * 解析响应消息中的文本内容。
     *
     * @param message  响应中的 message 节点
     * @param provider 平台，用于错误归因
     * @return 文本内容；字段缺失或为 null 时返回 null
     * @throws ClaraException 字段存在但不是文本时
     */
    private static Content textContent(JsonNode message, ModelProvider provider) {
        JsonNode content = message.get("content");
        if (content == null || content.isNull()) {
            return null;
        }
        if (!content.isTextual()) {
            throw ExceptionUtils.protocolError(provider);
        }
        return new TextContent(content.textValue());
    }

    /**
     * 解析响应消息中的工具调用内容。
     *
     * @param message  响应中的 message 节点
     * @param provider 平台，用于错误归因
     * @return 工具调用内容列表；字段缺失或为 null 时返回空列表
     * @throws ClaraException 字段存在但不是数组，或某个工具调用字段不完整时
     */
    private static List<Content> toolCallContents(JsonNode message, ModelProvider provider) {
        JsonNode calls = message.get("tool_calls");
        if (calls == null || calls.isNull()) {
            return Collections.emptyList();
        }
        if (!calls.isArray()) {
            throw ExceptionUtils.protocolError(provider);
        }
        List<Content> contents = new ArrayList<>();
        for (JsonNode call : calls) {
            contents.add(toolCallContent(call, provider));
        }
        return contents;
    }

    /**
     * 解析单个工具调用。
     *
     * <p>要求 id、函数名与参数字符串三者齐备，缺一即按协议错误处理。
     *
     * @param call     工具调用节点
     * @param provider 平台，用于错误归因
     * @return 工具调用内容
     * @throws ClaraException 任一必需字段缺失时
     */
    private static Content toolCallContent(JsonNode call, ModelProvider provider) {
        JsonNode function = call.get("function");
        String id = JsonNodeUtils.textOrNull(call.get("id"));
        String name = function == null ? null : JsonNodeUtils.textOrNull(function.get("name"));
        String arguments = function == null ? null : JsonNodeUtils.textOrNull(function.get("arguments"));
        if (id == null || name == null || arguments == null) {
            throw ExceptionUtils.protocolError(provider);
        }
        return new ToolCallContent(id, name, arguments);
    }

    /**
     * 解析 token 用量。
     *
     * <p>必填 {@code prompt_tokens} 与 {@code completion_tokens}，缺失时按 0 处理；可选
     * {@code total_tokens} 与缓存命中量按「缺失即 null」读取，以便上层按输入加输出推导总量，
     * 而不是把平台未提供误当成 0。
     *
     * @param value usage 节点，可为 null
     * @return token 用量，节点缺失时返回 null
     */
    private static TokenUsage usage(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return new TokenUsage(
                JsonNodeUtils.getIntValue(value, "prompt_tokens"),   //提示词输入token大小
                JsonNodeUtils.getIntValue(value, "completion_tokens"),//当前响应token大小
                JsonNodeUtils.optionalIntValue(value, "total_tokens"),//当前会话token总大小，缺失时为 null
                JsonNodeUtils.optionalIntValue(value, "cached_tokens") //当前请求命中缓存token大小，缺失时为 null
        );
    }
}
