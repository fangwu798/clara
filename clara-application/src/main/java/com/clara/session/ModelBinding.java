package com.clara.session;

import com.clara.kernel.config.ProviderConfiguration;

/**
 * 模型绑定：别名（或角色）解析出的「平台配置 + 可选模型标识」。
 *
 * <p>是什么、在哪一层：application 会话层（{@code com.clara.session}）的值对象，由 {@link ModelRegistry}
 * 解析产生，供工作流 AGENT 节点选择模型时使用。
 *
 * <p>语义：{@code model} 为 null / 空白表示「未显式指定模型」，调用方应退回该平台配置对应能力的默认模型
 * 标识；{@code configuration} 非 null，且其生命周期归 {@link ModelRegistry} 所有，使用方不得关闭。
 */
public final class ModelBinding {

    private final ProviderConfiguration configuration;
    private final String model;

    /**
     * 创建模型绑定。
     *
     * @param configuration 平台配置，不可为 null
     * @param model         模型标识，可为 null（表示退回能力默认模型）
     * @throws IllegalArgumentException configuration 为 null 时
     */
    public ModelBinding(ProviderConfiguration configuration, String model) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        this.configuration = configuration;
        this.model = model;
    }

    /**
     * 平台配置，非 null。
     *
     * @return 平台配置
     */
    public ProviderConfiguration configuration() {
        return configuration;
    }

    /**
     * 模型标识，未显式指定时 null。
     *
     * @return 模型标识
     */
    public String model() {
        return model;
    }
}
