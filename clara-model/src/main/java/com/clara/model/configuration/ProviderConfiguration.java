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
 * <p>注意：本类拥有凭据配置的生命周期，应使用 try-with-resources 或在应用关闭时调用
 * {@link #close()}。类型化转换只负责配置映射，不会注册模型、执行账号探测或授予模型能力；这些操作仍需
 * 宿主应用结合 {@code ProviderModelDescriptor} 完成。
 */
public final class ProviderConfiguration implements Closeable {
    private final ModelProvider provider;
    private final ProviderCredentialConfiguration credential;
    private final ProviderTransportConfiguration transport;
    private final Map<String, ProviderCapabilityConfiguration> capabilities;

    public ProviderConfiguration(ModelProvider provider,
            ProviderCredentialConfiguration credential,
            ProviderTransportConfiguration transport,
            Map<String, ProviderCapabilityConfiguration> capabilities) {
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

    public ModelProvider getProvider() {
        return provider;
    }

    public ProviderCredentialConfiguration getCredential() {
        return credential;
    }

    public ProviderTransportConfiguration getTransport() {
        return transport;
    }

    public Map<String, ProviderCapabilityConfiguration> getCapabilities() {
        return capabilities;
    }

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

    public ProviderCredentialResolver credentialResolver() {
        return credential.resolver();
    }

    public OpenAiCompatibleChatConfig toOpenAiCompatibleChatConfig(String name) {
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new OpenAiCompatibleChatConfig(provider, capability.getEndpoint(),
                capability.requireModelId(), capability.requireApiProfile(),
                credential.getSecretRef());
    }

    public OpenAiCompatibleEmbeddingConfig toOpenAiCompatibleEmbeddingConfig(String name) {
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new OpenAiCompatibleEmbeddingConfig(provider, capability.getEndpoint(),
                capability.requireModelId(), capability.requireApiProfile(),
                credential.getSecretRef(), capability.requireInt("dimension"),
                capability.requireInt("maxInputs"), capability.requireInt("maxTextChars"),
                capability.requireInt("maxTotalChars"));
    }

    public AnthropicChatConfig toAnthropicChatConfig(String name) {
        requireProvider(ModelProvider.ANTHROPIC);
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new AnthropicChatConfig(capability.getEndpoint(), capability.requireModelId(),
                capability.requireApiProfile(), capability.requireString("apiVersion"),
                credential.getSecretRef(), capability.requireInt("defaultMaxTokens"));
    }

    public DeepSeekModelCatalogConfig toDeepSeekModelCatalogConfig(String name) {
        requireProvider(ModelProvider.DEEPSEEK);
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new DeepSeekModelCatalogConfig(capability.getEndpoint(),
                capability.requireApiProfile(), credential.getSecretRef(),
                capability.requireInt("maxModels"), capability.requireInt("maxModelIdChars"));
    }

    public OpenAiMediaConfig toOpenAiMediaConfig(String name) {
        requireProvider(ModelProvider.OPENAI);
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new OpenAiMediaConfig(capability.getEndpoint(), capability.requireModelId(),
                credential.getSecretRef(), capability.requireInt("maxTextChars"),
                capability.requireInt("maxInputBytes"), capability.requireInt("maxOutputs"));
    }

    public VolcengineImageConfig toVolcengineImageConfig(String name) {
        requireProvider(ModelProvider.VOLCENGINE);
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new VolcengineImageConfig(capability.getEndpoint(), capability.requireModelId(),
                credential.getSecretRef(), capability.requireInt("maxPromptChars"),
                capability.requireInt("maxImages"), capability.requireInt("maxSourceImages"),
                capability.requireBoolean("watermark"));
    }

    public VolcengineVideoConfig toVolcengineVideoConfig(String name) {
        requireProvider(ModelProvider.VOLCENGINE);
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new VolcengineVideoConfig(capability.getEndpoint(), capability.requireModelId(),
                credential.getSecretRef(), capability.requireInt("maxPromptChars"),
                capability.requireInt("maxReferenceImages"));
    }

    public AlibabaAudioConfig toAlibabaAudioConfig(String name) {
        requireProvider(ModelProvider.ALIBABA);
        ProviderCapabilityConfiguration capability = getCapability(name);
        return new AlibabaAudioConfig(capability.getEndpoint(), capability.requireModelId(),
                credential.getSecretRef(), capability.requireInt("maxTextChars"));
    }

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

    @Override
    public void close() {
        credential.close();
    }

    private void requireProvider(ModelProvider expected) {
        if (provider != expected) {
            throw new IllegalArgumentException("configuration provider must be " + expected.name());
        }
    }
}
