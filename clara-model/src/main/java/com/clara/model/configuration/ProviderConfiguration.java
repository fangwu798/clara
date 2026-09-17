package com.clara.model.configuration;

import com.clara.media.MediaType;
import com.clara.model.api.ModelProvider;
import com.clara.model.provider.alibaba.AlibabaAudioConfig;
import com.clara.model.provider.alibaba.AlibabaNativeTaskConfig;
import com.clara.model.provider.anthropic.AnthropicChatConfig;
import com.clara.model.provider.compatible.OpenAiCompatibleChatConfig;
import com.clara.model.provider.compatible.OpenAiCompatibleEmbeddingConfig;
import com.clara.model.provider.deepseek.DeepSeekModelCatalogConfig;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.openai.OpenAiMediaConfig;
import com.clara.model.provider.volcengine.VolcengineImageConfig;
import com.clara.model.provider.volcengine.VolcengineVideoConfig;
import lombok.Data;

import java.io.Closeable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表示从单个平台 YAML 文件加载的完整模型配置。
 *
 * <p>常规用法：通过 {@link ProviderYamlConfigurationLoader} 加载文件，使用
 * {@link #getTransport()} 创建公共 HTTP 传输，再按能力名称调用相应的 {@code to...Config} 方法构造
 * 现有平台适配器。一个实例只对应一个 {@link ModelProvider}，一个平台的多个模型能力放在
 * {@code capabilities} 映射中。
 *
 * <p>以 DeepSeek 为例，从 YAML 文件加载配置后，可分别以非 SSE（同步）和 SSE（流式）方式调用对话模型：
 * <pre>{@code
 * try (ProviderConfiguration config = new ProviderYamlConfigurationLoader().load(Paths.get("config/model-providers/local/deepseek.yml"))) {
 *     // 准备对话配置、消息与调用上下文
 *     OpenAiCompatibleChatConfig chatConfig = config.toOpenAiCompatibleChatConfig("chat");
 *     List<ChatMessage> messages = Collections.singletonList(new ChatMessage(Role.USER,Collections.<Content>singletonList(new TextContent("你好")), null, null));
 *     InvocationContext context = new InvocationContext("tenant", "user", "run", "trace-1", System.currentTimeMillis() + 60000L, new CancellationToken());
 *     ModelParameters parameters = new ModelParameters(chatConfig.getModelId(), Double.valueOf(0.7d), null, Integer.valueOf(512), null, false);
 *     ChatRequest request = new ChatRequest(messages, parameters, null, null, context);
 *
 *     // 1. 非 SSE 同步调用：一次性返回完整响应
 *     ChatModel chatModel = new OpenAiCompatibleChatModel(chatConfig,
 *             new Jdk8ProviderHttpTransport(config.getTransport().toHttpTransportConfig()),
 *             config.credentialResolver());
 *     ChatResponse response = chatModel.chat(request);
 *     for (Content content : response.getMessage().getContents()) {
 *         if (content instanceof TextContent) {
 *             System.out.print(((TextContent) content).getText());
 *         }
 *     }
 *
 *     // 2. SSE 流式调用：通过 StreamObserver 增量接收文本
 *     ExecutorService executor = Executors.newSingleThreadExecutor();
 *     try {
 *         StreamingChatModel streaming = new OpenAiCompatibleStreamingChatModel(chatConfig,
 *                 new Jdk8ProviderSseTransport(config.getTransport().toHttpTransportConfig(),
 *                         8192, 65536, 100000),
 *                 config.credentialResolver(), executor, 8, 1000000);
 *         StreamHandle handle = streaming.chat(request, new StreamObserver() {
 *             public void onEvent(StreamEvent event) {
 *                 if (event.getType() == StreamEvent.Type.TEXT_DELTA) {
 *                     System.out.print(event.getPayload());
 *                 } else if (event.getType() == StreamEvent.Type.RESPONSE_COMPLETE) {
 *                     System.out.println();
 *                 }
 *             }
 *         });
 *         // 流式事件异步回调，handle 用于查询完成状态或取消
 *         while (!handle.isDone()) {
 *             Thread.sleep(50L);
 *         }
 *     } finally {
 *         executor.shutdown();
 *     }
 * }
 * }</pre>
 *
 * <p>注意：本类拥有凭据配置的生命周期，应使用 try-with-resources 或在应用关闭时调用
 * {@link #close()}。类型化转换只负责配置映射，不会注册模型、执行账号探测或授予模型能力；这些操作仍需
 * 宿主应用结合 {@code ProviderModelDescriptor} 完成。
 */
@Data
public final class ProviderConfiguration implements Closeable {
    /**
     * 模型提供平台
     */
    private final ModelProvider provider;
    /**
     * 凭据配置，拥有其生命周期。
     */
    private final ProviderCredentialConfiguration credential;
    /**
     * 平台共用的传输配置。
     */
    private final ProviderTransportConfiguration transport;
    /**
     * 能力名称到能力配置的只读映射。
     */
    private final Map<String, ProviderCapabilityConfiguration> capabilities;

    /**
     * 创建平台配置。
     *
     * @param provider     所属模型平台，不可为空
     * @param credential   凭据配置，不可为空
     * @param transport    传输配置，不可为空
     * @param capabilities 能力映射，不可为空且不得包含 null
     * @throws IllegalArgumentException 必填项为空或能力映射为空时
     */
    public ProviderConfiguration(ModelProvider provider, ProviderCredentialConfiguration credential, ProviderTransportConfiguration transport, Map<String, ProviderCapabilityConfiguration> capabilities) {
        if (provider == null || credential == null || transport == null) {
            throw new IllegalArgumentException("provider configuration must not contain null");
        }
        if (capabilities == null || capabilities.isEmpty()) {
            throw new IllegalArgumentException("capabilities must not be empty");
        }
        if (capabilities.containsKey(null) || capabilities.containsValue(null)) {
            throw new IllegalArgumentException("capabilities must not contain null");
        }
        this.provider = provider;
        this.credential = credential;
        this.transport = transport;
        this.capabilities = Collections.unmodifiableMap(
                new LinkedHashMap<String, ProviderCapabilityConfiguration>(capabilities));
    }

    /**
     * 按名称读取能力配置。
     *
     * @param name 能力名称
     * @return 对应的能力配置
     * @throws IllegalArgumentException 名称为空或能力未配置时
     */
    public ProviderCapabilityConfiguration getCapability(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("capability name must not be blank");
        }
        ProviderCapabilityConfiguration capability = capabilities.get(name);
        if (capability == null) {
            throw new IllegalArgumentException("capability is not configured: " + name);
        }
        return capability;
    }

    /**
     * 创建基于当前凭据配置的凭据解析器。
     *
     * @return 凭据解析器
     */
    public ProviderCredentialResolver credentialResolver() {
        return credential.resolver();
    }

    /**
     * 按能力名称构造 OpenAI 兼容对话配置。
     *
     * @param name 能力名称
     * @return OpenAI 兼容对话配置
     */
    public OpenAiCompatibleChatConfig toOpenAiCompatibleChatConfig(String name) {
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new OpenAiCompatibleChatConfig(provider, capability.getEndpoint(),
                capability.requireModelId(), capability.requireApiProfile(),
                credential.getSecretRef());
    }

    /**
     * 按能力名称构造 OpenAI 兼容嵌入配置。
     *
     * @param name 能力名称
     * @return OpenAI 兼容嵌入配置
     */
    public OpenAiCompatibleEmbeddingConfig toOpenAiCompatibleEmbeddingConfig(String name) {
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new OpenAiCompatibleEmbeddingConfig(provider, capability.getEndpoint(),
                capability.requireModelId(), capability.requireApiProfile(),
                credential.getSecretRef(), capability.requireInt("dimension"),
                capability.requireInt("maxInputs"), capability.requireInt("maxTextChars"),
                capability.requireInt("maxTotalChars"));
    }

    /**
     * 按能力名称构造 Anthropic 对话配置。
     *
     * @param name 能力名称
     * @return Anthropic 对话配置
     * @throws IllegalArgumentException 平台不是 Anthropic 时
     */
    public AnthropicChatConfig toAnthropicChatConfig(String name) {
        requireProvider(ModelProvider.ANTHROPIC);
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new AnthropicChatConfig(capability.getEndpoint(), capability.requireModelId(),
                capability.requireApiProfile(), capability.requireString("apiVersion"),
                credential.getSecretRef(), capability.requireInt("defaultMaxTokens"));
    }

    /**
     * 按能力名称构造 DeepSeek 模型目录配置。
     *
     * @param name 能力名称
     * @return DeepSeek 模型目录配置
     * @throws IllegalArgumentException 平台不是 DeepSeek 时
     */
    public DeepSeekModelCatalogConfig toDeepSeekModelCatalogConfig(String name) {
        requireProvider(ModelProvider.DEEPSEEK);
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new DeepSeekModelCatalogConfig(capability.getEndpoint(),
                capability.requireApiProfile(), credential.getSecretRef(),
                capability.requireInt("maxModels"), capability.requireInt("maxModelIdChars"));
    }

    /**
     * 按能力名称构造 OpenAI 媒体配置。
     *
     * @param name 能力名称
     * @return OpenAI 媒体配置
     * @throws IllegalArgumentException 平台不是 OpenAI 时
     */
    public OpenAiMediaConfig toOpenAiMediaConfig(String name) {
        requireProvider(ModelProvider.OPENAI);
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new OpenAiMediaConfig(capability.getEndpoint(), capability.requireModelId(),
                credential.getSecretRef(), capability.requireInt("maxTextChars"),
                capability.requireInt("maxInputBytes"), capability.requireInt("maxOutputs"));
    }

    /**
     * 按能力名称构造火山引擎图片生成配置。
     *
     * @param name 能力名称
     * @return 火山引擎图片生成配置
     * @throws IllegalArgumentException 平台不是 Volcengine 时
     */
    public VolcengineImageConfig toVolcengineImageConfig(String name) {
        requireProvider(ModelProvider.VOLCENGINE);
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new VolcengineImageConfig(capability.getEndpoint(), capability.requireModelId(),
                credential.getSecretRef(), capability.requireInt("maxPromptChars"),
                capability.requireInt("maxImages"), capability.requireInt("maxSourceImages"),
                capability.requireBoolean("watermark"));
    }

    /**
     * 按能力名称构造火山引擎视频生成配置。
     *
     * @param name 能力名称
     * @return 火山引擎视频生成配置
     * @throws IllegalArgumentException 平台不是 Volcengine 时
     */
    public VolcengineVideoConfig toVolcengineVideoConfig(String name) {
        requireProvider(ModelProvider.VOLCENGINE);
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new VolcengineVideoConfig(capability.getEndpoint(), capability.requireModelId(),
                credential.getSecretRef(), capability.requireInt("maxPromptChars"),
                capability.requireInt("maxReferenceImages"));
    }

    /**
     * 按能力名称构造阿里音频配置。
     *
     * @param name 能力名称
     * @return 阿里音频配置
     * @throws IllegalArgumentException 平台不是 Alibaba 时
     */
    public AlibabaAudioConfig toAlibabaAudioConfig(String name) {
        requireProvider(ModelProvider.ALIBABA);
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new AlibabaAudioConfig(capability.getEndpoint(), capability.requireModelId(),
                credential.getSecretRef(), capability.requireInt("maxTextChars"));
    }

    /**
     * 按能力名称构造阿里原生异步任务配置。
     *
     * @param name 能力名称
     * @return 阿里原生异步任务配置
     * @throws IllegalArgumentException 平台不是 Alibaba，或 outputType 非法时
     */
    public AlibabaNativeTaskConfig toAlibabaNativeTaskConfig(String name) {
        requireProvider(ModelProvider.ALIBABA);
        ProviderCapabilityConfiguration capability = getCapability(name);
        MediaType outputType;
        try {
            outputType = MediaType.valueOf(capability.requireString("outputType"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("outputType is invalid", exception);
        }
        return new AlibabaNativeTaskConfig(capability.getEndpoint(),
                capability.requireTaskQueryEndpoint(), capability.requireModelId(),
                credential.getSecretRef(), outputType,
                capability.requireString("outputMimeType"),
                capability.requireInt("maxPromptChars"),
                capability.requireInt("maxInputImages"));
    }

    /**
     * 关闭并释放凭据配置。
     */
    @Override
    public void close() {
        credential.close();
    }

    /**
     * 校验当前配置的平台与期望平台一致。
     *
     * @param expected 期望的模型平台
     * @throws IllegalArgumentException 平台不一致时
     */
    private void requireProvider(ModelProvider expected) {
        if (provider != expected) {
            throw new IllegalArgumentException("configuration provider must be " + expected.name());
        }
    }
}
