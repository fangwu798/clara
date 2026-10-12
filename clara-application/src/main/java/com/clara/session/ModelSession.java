package com.clara.session;

import com.clara.config.ConfigLocator;
import com.clara.kernel.config.ProviderConfiguration;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.List;

/**
 * 会话级模型状态：持有 {@link ModelRegistry} 与「当前默认别名」，提供运行期切换能力。
 *
 * <p>是什么、在哪一层：application 的会话状态持有者（{@code com.clara.session}），把「当前用哪个模型」
 * 从入口主流程的局部变量提升为对象，便于指令处理器在 {@code /model} 中安全地切换默认模型，而无需在业务
 * 主流程里散落可变状态。
 *
 * <p>职责边界：模型配置的加载与生命周期统一由 {@link ModelRegistry} 负责，本类只维护「默认别名」并把它
 * 绑定到注册表；{@link #close()} 不关闭注册表持有的配置（避免二次关闭），由宿主统一关闭注册表。
 */
public final class ModelSession implements Closeable {

    private final ModelRegistry registry;
    private final List<Path> candidates;
    private String alias;
    private Path configPath;

    /**
     * 创建会话级模型状态，并把初始配置绑定为默认模型。
     *
     * @param registry   模型绑定注册表，不可为 null
     * @param candidates 可选模型配置清单，非 null（可为空）
     * @param configPath 初始配置文件路径，不可为 null
     * @throws IllegalArgumentException registry 或 configPath 为 null 时
     */
    public ModelSession(ModelRegistry registry, List<Path> candidates, Path configPath) {
        if (registry == null || configPath == null) {
            throw new IllegalArgumentException("registry and configPath must not be null");
        }
        this.registry = registry;
        this.candidates = candidates;
        this.configPath = configPath;
        this.alias = ConfigLocator.aliasOf(configPath);
        registry.bindDefault(alias);
    }

    /**
     * 当前默认模型配置。
     *
     * @return 模型配置，非 null
     */
    public ProviderConfiguration providerConfig() {
        return registry.binding(alias).configuration();
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
     * 切换到指定配置：更新默认别名（不再重复加载 / 关闭，配置由注册表按需加载并统一持有）。
     *
     * @param next 目标配置路径，不可为 null
     */
    public void switchTo(Path next) {
        if (next == null) {
            throw new IllegalArgumentException("next must not be null");
        }
        this.alias = ConfigLocator.aliasOf(next);
        this.configPath = next;
        registry.bindDefault(alias);
    }

    /**
     * 关闭会话：模型配置生命周期归 {@link ModelRegistry}，这里不关闭。
     */
    @Override
    public void close() {
        // 模型配置由 ModelRegistry 统一持有与关闭，避免二次关闭
    }
}
