package com.clara.workflow.application;

import com.clara.kernel.config.ProviderConfiguration;

/**
 * 工作流模型上下文：保存当前会话绑定的模型平台配置与模型标识。
 *
 * <p>是什么、在哪一层：application 工作流装配层（包 {@code com.clara.workflow.application}）的会话级
 * 状态载体。AGENT 节点调用模型需要平台配置，而装配期拿不到（配置是会话级），故由宿主（CLI）在加载 / 切换
 * 模型配置后经 {@link #use(ProviderConfiguration, String)} 注入。
 *
 * <p>线程安全：字段为 volatile，读写轻量；绑定前 {@link #isReady()} 为 {@code false}。
 */
public final class WorkflowModelContext {

    private volatile ProviderConfiguration configuration;
    private volatile String model;

    /**
     * 绑定模型平台配置与模型标识。
     *
     * @param configuration 平台配置，可为 null（表示未绑定）
     * @param model         模型标识，可为 null（表示退回平台 CHAT 能力默认模型）
     */
    public void use(ProviderConfiguration configuration, String model) {
        this.configuration = configuration;
        this.model = model;
    }

    /**
     * 当前绑定的平台配置。
     *
     * @return 平台配置，未绑定时 null
     */
    public ProviderConfiguration configuration() {
        return configuration;
    }

    /**
     * 当前绑定的模型标识。
     *
     * @return 模型标识，未指定时 null
     */
    public String model() {
        return model;
    }

    /**
     * 是否已绑定平台配置。
     *
     * @return 已绑定时 {@code true}
     */
    public boolean isReady() {
        return configuration != null;
    }
}
