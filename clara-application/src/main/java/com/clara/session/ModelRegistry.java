package com.clara.session;

import com.clara.config.ConfigLocator;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 模型绑定注册表：按别名管理多份平台配置与可选的角色表，是「多角色 / 多模型」的配置来源。
 *
 * <p>是什么、在哪一层：application 会话层（{@code com.clara.session}）的状态组件，取代原先只保存单份
 * 绑定的工作流模型上下文；AGENT 工作流节点据此按「角色 / 模型别名」取到各自的 {@link ProviderConfiguration}。
 *
 * <p>别名规则：别名 = 模型配置文件名去掉 {@code yml} / {@code yaml} 扩展名（与
 * {@link ConfigLocator#aliasOf(Path)} 口径一致），由构造期的候选配置列表建立索引。
 *
 * <p>角色表：{@code role → 别名}；由宿主从 {@code config/model-roles.yml} 读取后经构造期传入。未声明的
 * 角色直接作为别名解析。
 *
 * <p>加载语义：**按需惰性加载**——首次解析命中别名时才加载该配置文件并缓存，避免启动期加载全部配置。
 * 未命中别名一律抛 {@link IllegalArgumentException}（配置问题），不静默降级。
 *
 * <p>生命周期与线程安全：本类拥有已加载平台配置的生命周期，{@link #close()} 统一关闭；解析与关闭同步，
 * 可被工作流并行分支并发调用。
 */
public final class ModelRegistry implements Closeable {

    /**
     * 别名（小写）到配置文件的索引。
     */
    private final Map<String, Path> configFiles;

    /**
     * 角色（小写）到别名（小写）的映射。
     */
    private final Map<String, String> roleAliases;

    /**
     * 已加载平台配置（键为小写别名）。
     */
    private final Map<String, ProviderConfiguration> loaded;

    private volatile String defaultAlias;

    /**
     * 创建模型绑定注册表。
     *
     * @param configFiles  候选模型配置文件，可为 null（视为无可选模型）；别名冲突时后者覆盖前者
     * @param roleBindings 角色到别名的映射，可为 null（视为无角色表）
     */
    public ModelRegistry(List<Path> configFiles, Map<String, String> roleBindings) {
        Map<String, Path> index = new LinkedHashMap<String, Path>();
        if (configFiles != null) {
            for (Path file : configFiles) {
                String alias = ConfigLocator.aliasOf(file);
                if (notBlank(alias)) {
                    index.put(normalize(alias), file);
                }
            }
        }
        this.configFiles = index;
        Map<String, String> roles = new LinkedHashMap<String, String>();
        if (roleBindings != null) {
            for (Map.Entry<String, String> entry : roleBindings.entrySet()) {
                if (notBlank(entry.getKey()) && notBlank(entry.getValue())) {
                    roles.put(normalize(entry.getKey()), normalize(entry.getValue()));
                }
            }
        }
        this.roleAliases = roles;
        this.loaded = new LinkedHashMap<String, ProviderConfiguration>();
    }

    /**
     * 绑定会话默认别名（无角色 / 模型指定的 AGENT 节点退回此别名）。
     *
     * @param alias 模型别名，不可为空白
     * @throws IllegalArgumentException alias 为空白时
     */
    public void bindDefault(String alias) {
        if (blank(alias)) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        this.defaultAlias = alias;
    }

    /**
     * 当前默认别名。
     *
     * @return 默认别名，未绑定时 null
     */
    public String defaultAlias() {
        return defaultAlias;
    }

    /**
     * 已建立索引的模型别名（小写）。
     *
     * @return 别名集合，非 null
     */
    public Set<String> aliases() {
        return Collections.unmodifiableSet(new TreeSet<String>(configFiles.keySet()));
    }

    /**
     * 按别名解析模型绑定。
     *
     * @param alias 模型别名，不可为空白
     * @return 模型绑定（模型标识为 null，退回能力默认模型），非 null
     * @throws IllegalArgumentException 别名为空白或未建立索引时
     */
    public synchronized ModelBinding binding(String alias) {
        return new ModelBinding(configuration(alias), null);
    }

    /**
     * 解析 AGENT 节点应使用的模型绑定：{@code modelAlias} 优先，其次 {@code role}，最后默认别名。
     *
     * @param role       角色名，可为空白
     * @param modelAlias 模型别名，可为空白
     * @return 模型绑定，非 null
     * @throws IllegalStateException    三者皆未指定时
     * @throws IllegalArgumentException 命中别名未建立索引时
     */
    public synchronized ModelBinding resolve(String role, String modelAlias) {
        String alias;
        if (notBlank(modelAlias)) {
            alias = modelAlias;
        } else if (notBlank(role)) {
            String mapped = roleAliases.get(normalize(role));
            alias = mapped == null ? role : mapped;
        } else {
            alias = defaultAlias;
        }
        if (blank(alias)) {
            throw new IllegalStateException("工作流 AGENT 节点尚未绑定模型：未指定 role / model，且未设置默认模型");
        }
        return new ModelBinding(configuration(alias), null);
    }

    private ProviderConfiguration configuration(String alias) {
        if (blank(alias)) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        String key = normalize(alias);
        ProviderConfiguration cached = loaded.get(key);
        if (cached != null) {
            return cached;
        }
        Path file = configFiles.get(key);
        if (file == null) {
            throw new IllegalArgumentException("模型别名不存在：" + alias + "，可用：" + aliases());
        }
        ProviderConfiguration configuration = new ProviderYamlConfigurationLoader().load(file);
        loaded.put(key, configuration);
        return configuration;
    }

    /**
     * 关闭全部已加载的平台配置。
     */
    @Override
    public synchronized void close() {
        for (ProviderConfiguration configuration : loaded.values()) {
            try {
                configuration.close();
            } catch (RuntimeException ignored) {
                // 释放失败不阻断其余配置关闭
            }
        }
        loaded.clear();
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
