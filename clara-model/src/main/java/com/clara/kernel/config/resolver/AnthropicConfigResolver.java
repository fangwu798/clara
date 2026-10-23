package com.clara.kernel.config.resolver;

import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.api.config.IConfigResolver;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderCapabilityConfiguration;
import com.clara.kernel.config.ProviderConfiguration;

/**
 * Anthropic（Claude）平台的配置解析器。
 *
 * <p>只有对话能力：Messages API 与 OpenAI 兼容家族的请求 / 响应线格式不同，但配置项形状一致
 * （端点、模型 ID、协议档位、凭据引用），因此与 {@link CompatibleConfigResolver} 分开登记，
 * 避免把 ANTHROPIC 误认为兼容家族成员。
 *
 * <p>无状态、线程安全；由 {@link ConfigResolvers} 在类初始化时登记。
 */
public final class AnthropicConfigResolver implements IConfigResolver {

    @Override
    public boolean supports(ModelProviderEnum provider, CapabilityKindEnum kind) {
        return provider == ModelProviderEnum.ANTHROPIC && kind == CapabilityKindEnum.CHAT;
    }

    @Override
    public ModelConfig resolve(ProviderConfiguration provider, CapabilityKindEnum kind) {
        ProviderCapabilityConfiguration capability = provider.getCapability(kind);
        return new ModelConfig(
                provider.getProvider(),
                kind,
                capability.getEndpoint(),
                capability.requireModelId(),
                capability.requireApiProfile(),
                capability.getProtocol(),
                provider.getCredential().getSecretRef());
    }
}
