package com.clara.kernel.config.resolver;


import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.api.config.IConfigResolver;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderCapabilityConfiguration;
import com.clara.kernel.config.ProviderConfiguration;


import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * OpenAI 兼容协议家族的配置解析器，覆盖对话与图片生成两种能力种类。
 *
 * <p>用途与位置：实现层组件，负责 OPENAI / DEEPSEEK / VOLCENGINE / ALIBABA 四家在
 * {@link CapabilityKindEnum#CHAT} 与 {@link CapabilityKindEnum#IMAGE} 上的配置解析——这四家的对话接口
 * （{@code /chat/completions}）与图片生成接口（{@code /images/generations}）都走 OpenAI 兼容协议，
 * 所需字段也完全相同（端点、模型 ID、协议档位、凭据引用），因此共用同一份解析逻辑。
 *
 * <p>使用方法：由 {@link ConfigResolvers} 在类初始化时构造并登记，调用方不直接 new——
 * 通过 {@code ProviderConfiguration#toConfig(CapabilityKind)} 间接使用。本类无状态，
 * 可被多线程并发调用，无需 {@code close()}。
 *
 * <p>失败语义：能力节点未配置，或缺少 {@code modelId} / {@code apiProfile} 等必填参数时抛
 * {@link IllegalArgumentException}。
 */
public final class CompatibleConfigResolver implements IConfigResolver {
    /**
     * 使用 OpenAI 兼容协议的平台集合，非 null。
     */
    private static final Set<ModelProviderEnum> PROVIDERS = Collections.unmodifiableSet(EnumSet.of(ModelProviderEnum.OPENAI, ModelProviderEnum.DEEPSEEK, ModelProviderEnum.VOLCENGINE, ModelProviderEnum.ALIBABA));

    /**
     * 本解析器覆盖的能力种类，非 null。
     */
    private static final Set<CapabilityKindEnum> KIND = Collections.unmodifiableSet(
            EnumSet.of(CapabilityKindEnum.CHAT, CapabilityKindEnum.IMAGE));

    /**
     * 判断平台是否属于兼容协议家族，且能力种类为对话或图片生成。
     *
     * @param provider 平台，可为 null
     * @param kind     能力种类，可为 null
     * @return 平台属于兼容协议家族且种类受支持时返回 true
     */
    @Override
    public boolean supports(ModelProviderEnum provider, CapabilityKindEnum kind) {
        return provider != null && kind != null && KIND.contains(kind) && PROVIDERS.contains(provider);
    }

    /**
     * 按能力种类构造 OpenAI 兼容配置。
     *
     * @param provider       已加载的平台配置，不可为 null，其平台必须是兼容协议家族成员
     * @param kind           能力种类，必须是 {@link CapabilityKindEnum#CHAT} 或 {@link CapabilityKindEnum#IMAGE}
     * @return OpenAI 兼容配置，非 null
     * @throws IllegalArgumentException 能力未配置或必填参数缺失、类型不符时
     */
    @Override
    public ModelConfig resolve(ProviderConfiguration provider,CapabilityKindEnum kind) {
        ProviderCapabilityConfiguration capability = provider.getCapability(kind);
        return new ModelConfig(
                provider.getProvider(),
                kind,
                capability.getEndpoint(),
                capability.requireModelId(),
                capability.requireApiProfile(),
                provider.getCredential().getSecretRef());
    }
}
