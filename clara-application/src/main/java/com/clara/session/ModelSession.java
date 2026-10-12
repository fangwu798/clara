package com.clara.session;

import com.clara.ApplicationBootstrap;
import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.List;

/**
 * 会话级模型状态：持有当前生效的模型配置、其配置文件路径与可选模型清单，并提供运行时切换能力。
 *
 * <p>是什么、在哪一层：application 的会话状态持有者（{@code com.clara.session}），把「当前用哪个模型」从
 * 入口主流程的局部变量提升为对象，便于指令处理器在 {@code /model} 中安全地热切换，而无需在业务主流程里
 * 散落可变状态。
 *
 * <p>生命周期：当前模型配置由本类负责关闭（{@code close()} 关闭当前 {@code providerConfig}）；切换时先加载
 * 新配置，再关闭旧配置，最后把新配置绑定到工作流模型上下文。
 */
public final class ModelSession implements Closeable {

    private final ApplicationBootstrap bootstrap;
    private final List<Path> candidates;
    private ProviderConfiguration providerConfig;
    private Path configPath;

    /**
     * 创建会话级模型状态。
     *
     * @param bootstrap      已装配的引导器，用于把模型绑定到工作流模型上下文，不可为 null
     * @param candidates     可选模型配置清单，非 null（可为空）
     * @param providerConfig 初始模型配置，不可为 null
     * @param configPath     初始配置路径，不可为 null
     */
    public ModelSession(ApplicationBootstrap bootstrap, List<Path> candidates, ProviderConfiguration providerConfig, Path configPath) {
        this.bootstrap = bootstrap;
        this.candidates = candidates;
        this.providerConfig = providerConfig;
        this.configPath = configPath;
    }

    /**
     * 当前生效的模型配置。
     *
     * @return 模型配置，非 null
     */
    public ProviderConfiguration providerConfig() {
        return providerConfig;
    }

    /**
     * 当前生效的配置文件路径。
     *
     * @return 配置路径，非 null
     */
    public Path configPath() {
        return configPath;
    }

    /**
     * 可选模型配置清单。
     *
     * @return 候选配置，非 null（可为空）
     */
    public List<Path> candidates() {
        return candidates;
    }

    /**
     * 是否存在可切换的候选模型。
     *
     * @return 有候选时为 true
     */
    public boolean canSwitch() {
        return candidates != null && !candidates.isEmpty();
    }

    /**
     * 切换到指定配置：加载新配置 → 关闭旧配置 → 绑定到工作流模型上下文。
     *
     * @param next 目标配置路径，不可为 null
     */
    public void switchTo(Path next) {
        ProviderConfiguration reloaded = new ProviderYamlConfigurationLoader().load(next);
        providerConfig.close();
        providerConfig = reloaded;
        configPath = next;
        bootstrap.workflowModelContext().use(providerConfig,
                providerConfig.toConfig(CapabilityKindEnum.CHAT).getModelId());
    }

    /**
     * 关闭当前模型配置。
     */
    @Override
    public void close() {
        if (providerConfig != null) {
            providerConfig.close();
        }
    }
}
