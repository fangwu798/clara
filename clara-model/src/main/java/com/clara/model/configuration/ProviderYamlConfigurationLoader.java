package com.clara.model.configuration;

import com.clara.kernel.config.ProviderConfiguration;

import java.nio.file.Path;

/**
 * 平台 YAML 配置的加载入口（门面）。
 *
 * <p>是什么、在哪一层：本类属于 {@code clara-model} 的配置解析层，对外只有一个动作——把单个平台的
 * YAML 文件读成 {@link ProviderConfiguration}。字段级别的形状声明交给配置类上的 {@code @JsonCreator} /
 * {@code @JsonProperty} 注解，文件与解析级别的严格校验交给 {@link StrictYamlBinder}，本类不含任何逐字段
 * 解析逻辑，也不保留白名单表。
 *
 * <p>谁创建谁调用：由宿主应用或测试创建（每个平台一个加载器实例足够，可复用），在装配阶段调用
 * {@link #load(Path)}；解析结果的生命周期由调用方管理，用完需 {@code close()}。
 *
 * <p>使用方法：
 * <ol>
 *   <li>为每个平台准备一个独立的 {@code .yml} / {@code .yaml} 文件（不扫描目录、不合并多平台）；</li>
 *   <li>{@code ProviderConfiguration config = new ProviderYamlConfigurationLoader().load(path);}</li>
 *   <li>按需取 {@code config.getTransport()}、{@code config.credentialResolver()} 与
 *       {@code config.toConfig(CapabilityKind)}；</li>
 *   <li>用完后 {@code config.close()}（推荐 try-with-resources）。</li>
 * </ol>
 *
 * <p>生命周期与线程安全：本类只持有一个配置完成的 {@link StrictYamlBinder}，不可变且线程安全，可并发
 * 加载不同文件；本类自身不需要关闭。
 *
 * <p>失败语义：文件不合规、存在未知配置项、缺必填项、类型不符、URI 不安全、扩展参数非标量、构造期校验
 * 失败时统一抛 {@link IllegalArgumentException}，消息为**中文并带字段路径与行号**（由
 * {@link StrictYamlBinder} 翻译）。配置错误不会回退默认值，也不会被静默忽略——键名写错会直接报
 * 「未知配置项」（本项目不做旧键兼容）。
 *
 * <p>注意：错误消息不会包含密钥值，但内联密钥仍只应放在被 Git 忽略的本地目录；可提交样例应使用环境变量名。
 *
 * @see StrictYamlBinder
 */
public final class ProviderYamlConfigurationLoader {

    /**
     * 默认配置文件大小上限，即 1 MiB。
     */
    private static final long DEFAULT_MAX_FILE_BYTES = 1024L * 1024L;

    /**
     * 实际执行文件校验与严格绑定的绑定器。
     */
    private final StrictYamlBinder binder;

    /**
     * 使用默认的 1 MiB 文件大小上限创建加载器。
     */
    public ProviderYamlConfigurationLoader() {
        this(DEFAULT_MAX_FILE_BYTES);
    }

    /**
     * 使用指定的文件大小上限创建加载器。
     *
     * @param maxFileBytes 文件大小上限，单位字节，必须为正数
     * @throws IllegalArgumentException maxFileBytes 非正时
     */
    public ProviderYamlConfigurationLoader(long maxFileBytes) {
        this.binder = new StrictYamlBinder(maxFileBytes);
    }

    /**
     * 加载并严格校验单个平台 YAML 文件。
     *
     * @param file 配置文件路径，必须是普通 {@code .yml} / {@code .yaml} 文件且不是符号链接
     * @return 解析得到的平台配置，非 null；调用方负责在使用后关闭它
     * @throws IllegalArgumentException 文件不合规或内容非法时（中文消息，带字段路径与行号）
     */
    public ProviderConfiguration load(Path file) {
        return binder.bind(file, ProviderConfiguration.class);
    }
}
