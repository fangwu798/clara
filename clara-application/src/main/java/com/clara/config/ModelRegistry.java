package com.clara.config;

import com.clara.kernel.config.ProviderConfiguration;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 模型注册表：按别名管理多份平台配置与可选的角色表，并记住「本会话当前生效的模型」。
 *
 * <p>是什么、在哪一层：application 配置层（{@code com.clara.config}）的状态组件，与 {@link ConfigLocator}
 * 同层——配置的定位归 {@link ConfigLocator}，配置的加载、缓存与生命周期归本类。它承担两件事：其一，
 * AGENT / REVIEW 工作流节点按「角色 / 模型别名」取到各自的 {@link ProviderConfiguration}；其二，持有会话
 * 当前生效的模型（即 {@code /model} 的写入点）。
 *
 * <p>为什么把原 {@code ModelSession} 合并进来：会话状态只有「当前配置路径 + 当前别名」，而别名解析与配置
 * 加载本就在本类，拆两个类会让同一份别名两处持有、并让「谁负责 close」产生歧义；合并后当前别名即工作流
 * 节点未指定 role / model 时的退回目标，配置生命周期仍只有本类一个 owner。
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
 * <p>生命周期与线程安全：本类拥有已加载平台配置的生命周期，{@link #close()} 统一关闭。可变状态只有「会话
 * 当前模型（别名 + 配置路径）」与「已加载配置缓存」两处，**读写全部在本对象的监视器下**：别名与路径是一个
 * 逻辑单元、必须成对生效，给两个字段各加 {@code volatile} 只能保证单个字段可见、并不能让调用方读到一致的
 * 组合，因此这里统一用 {@code synchronized} 建立 happens-before，而不是字段级 {@code volatile}。工作流并行
 * 分支可安全并发调用；{@link #aliases()} 只读「构造后不再变化」的索引，无需加锁。
 */
public final class ModelRegistry implements Closeable {

    /**
     * 别名（小写）到配置文件的索引；构造后不再变化（{@code unmodifiableMap}），读取无需加锁。
     */
    private final Map<String, Path> configFiles;

    /**
     * 角色（小写）到别名（小写）的映射；构造后不再变化（{@code unmodifiableMap}），读取无需加锁。
     */
    private final Map<String, String> roleAliases;

    /**
     * 已加载平台配置缓存（键为小写别名）；只在 {@link #configuration(String)} 内写入，全程持锁。
     */
    private final Map<String, ProviderConfiguration> loaded;

    /**
     * 当前生效的别名，未选择模型时为 null；与 {@link #currentConfigPath} 待在同一监视器下读写。
     */
    private String currentAlias;

    /**
     * 当前生效的配置文件路径，未选择模型时为 null；与 {@link #currentAlias} 待在同一监视器下读写。
     */
    private Path currentConfigPath;

    /**
     * 创建模型注册表。
     *
     * @param configFiles  候选模型配置文件，可为 null（视为无可选模型）；别名冲突时后者覆盖前者
     * @param roleBindings 角色到别名的映射，可为 null（视为无角色表）
     */
    public ModelRegistry(List<Path> configFiles, Map<String, String> roleBindings) {
        Map<String, Path> index = new LinkedHashMap<>();
        if (configFiles != null) {
            for (Path file : configFiles) {
                String alias = ConfigLocator.aliasOf(file);
                if (notBlank(alias)) {
                    index.put(normalize(alias), file);
                }
            }
        }
        this.configFiles = Collections.unmodifiableMap(index);
        Map<String, String> roles = new LinkedHashMap<>();
        if (roleBindings != null) {
            for (Map.Entry<String, String> entry : roleBindings.entrySet()) {
                if (notBlank(entry.getKey()) && notBlank(entry.getValue())) {
                    roles.put(normalize(entry.getKey()), normalize(entry.getValue()));
                }
            }
        }
        this.roleAliases = Collections.unmodifiableMap(roles);
        this.loaded = new LinkedHashMap<>();
    }

    /**
     * 选择会话当前生效的模型配置：记录配置路径，并把该别名作为工作流节点的退回目标。
     *
     * <p>为什么在这里校验别名：别名与文件名口径不一致属于配置问题，选择时立即失败比等到发起模型调用才报错
     * 更好定位；校验口径与 {@link #resolve(String, String)} 一致，都要求别名已建立索引。
     *
     * @param configPath 目标模型配置文件，不可为 null
     * @throws IllegalArgumentException configPath 为 null，或其别名未建立索引时
     */
    public synchronized void select(Path configPath) {
        if (configPath == null) {
            throw new IllegalArgumentException("configPath must not be null");
        }
        String alias = ConfigLocator.aliasOf(configPath);
        if (blank(alias) || !configFiles.containsKey(normalize(alias))) {
            throw new IllegalArgumentException("模型别名不存在：" + alias + "，可用：" + aliases());
        }
        this.currentAlias = alias;
        this.currentConfigPath = configPath;
    }

    /**
     * 当前生效的模型别名。
     *
     * <p>加锁读：必须与 {@link #currentConfigPath()} 成对一致，见类注释的线程安全约定。
     *
     * @return 别名，未选择模型时为 null
     */
    public synchronized String currentAlias() {
        return currentAlias;
    }

    /**
     * 当前生效的配置文件路径。
     *
     * <p>加锁读：必须与 {@link #currentAlias()} 成对一致，见类注释的线程安全约定。
     *
     * @return 配置路径，未选择模型时为 null
     */
    public synchronized Path currentConfigPath() {
        return currentConfigPath;
    }

    /**
     * 当前生效的平台配置。
     *
     * @return 平台配置，非 null
     * @throws IllegalStateException 尚未选择模型时
     * @throws IllegalArgumentException 当前别名未建立索引时
     */
    public synchronized ProviderConfiguration currentConfiguration() {
        if (blank(currentAlias)) {
            throw new IllegalStateException("尚未选择模型配置");
        }
        return configuration(currentAlias);
    }

    /**
     * 已建立索引的模型别名（小写）。
     *
     * <p>不加锁：索引在构造期一次性建立后不再变化。
     *
     * @return 别名集合，非 null
     */
    public Set<String> aliases() {
        return Collections.unmodifiableSet(new TreeSet<>(configFiles.keySet()));
    }

    /**
     * 解析工作流节点应使用的平台配置：{@code modelAlias} 优先，其次 {@code role}，最后当前生效的别名。
     *
     * @param role       角色名，可为空白
     * @param modelAlias 模型别名，可为空白
     * @return 平台配置，非 null
     * @throws IllegalStateException    三者皆未指定时
     * @throws IllegalArgumentException 命中别名未建立索引时
     */
    public synchronized ProviderConfiguration resolve(String role, String modelAlias) {
        String alias;
        if (notBlank(modelAlias)) {
            alias = modelAlias;
        } else if (notBlank(role)) {
            String mapped = roleAliases.get(normalize(role));
            alias = mapped == null ? role : mapped;
        } else {
            alias = currentAlias;
        }
        if (blank(alias)) {
            throw new IllegalStateException("工作流 AGENT 节点尚未绑定模型：未指定 role / model，且未设置默认模型");
        }
        return configuration(alias);
    }

    /**
     * 取别名对应的平台配置：命中缓存直接返回，否则加载并写入缓存。
     *
     * <p>加锁：本方法会写 {@code loaded} 缓存；调用方虽已持锁，这里再声明一次，保证缓存写入永远在监视器内，
     * 不依赖调用方自觉。
     *
     * @param alias 模型别名
     * @return 平台配置，非 null
     * @throws IllegalArgumentException 别名为空白或未建立索引时
     */
    private synchronized ProviderConfiguration configuration(String alias) {
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