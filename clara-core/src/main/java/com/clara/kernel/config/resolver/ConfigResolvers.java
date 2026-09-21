package com.clara.kernel.config.resolver;

import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.api.config.IConfigResolver;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.exception.ClaraException;
import com.clara.kernel.exception.ErrorCategory;
import com.clara.kernel.exception.ErrorCodes;
import com.clara.enums.ModelProviderEnum;

import com.clara.utils.ExceptionUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 配置解析器注册表：按「平台 × 能力种类」查找唯一负责的 {@link IConfigResolver}。
 *
 * <p>用途与位置：实现层组件，是配置解析的唯一入口。{@code ProviderConfiguration#toConfig(CapabilityKind)}
 * 直接委托本类，因此新增平台或能力只需要新增一个 resolver 并登记到 {@link #RESOLVERS}，
 * {@code ProviderConfiguration} 不再改动。
 *
 * <p>使用方法：
 * <ol>
 *   <li>无需实例化——构造器私有，全部方法为 {@code static}；</li>
 *   <li>常规路径：{@code providerConfiguration.toConfig(CapabilityKind.CHAT)}；</li>
 *   <li>需要先确认组合是否受支持时：{@link #forProvider(ModelProviderEnum, CapabilityKindEnum)}
 *       返回唯一实现，或在不受支持时抛配置异常；</li>
 *   <li>无状态、线程安全（解析器列表初始化后只读），无需 {@code close()}
 *   —— 解析结果的生命周期由调用方持有。</li>
 * </ol>
 *
 * <p>失败语义：
 * <ul>
 *   <li>平台与能力种类没有对应 resolver 时，抛 {@code ClaraException}，错误码
 *       {@link ErrorCodes#CAPABILITY_NOT_SUPPORTED}、分类 {@link ErrorCategory#CONFIGURATION}、不可重试；</li>
 *   <li>两个 resolver 声明了同一组合时，类初始化期抛 {@link IllegalStateException}
 *       ——属于编码错误，必须在启动前修正；</li>
 *   <li>解析过程中能力节点或必填参数不合法时，由具体 resolver 抛 {@link IllegalArgumentException}。</li>
 * </ul>
 */
public final class ConfigResolvers {
    /**
     * 已登记的平台解析器，按「先兼容协议对话、再平台专有能力」的顺序排列；初始化后只读，不可变。
     */
    private static final List<IConfigResolver> RESOLVERS = Collections.unmodifiableList(
            Arrays.<IConfigResolver>asList(
                    new CompatibleConfigResolver()
            ));

    static {
        validateUniqueRegistration();
    }

    private ConfigResolvers() {
    }

    /**
     * 查找负责指定平台与能力种类的唯一解析器。
     *
     * @param provider 平台，可为 null（为 null 时必然不受支持，直接抛配置异常）
     * @param kind     能力种类，可为 null（为 null 时必然不受支持，直接抛配置异常）
     * @return 唯一负责该组合的解析器，非 null
     * @throws ClaraException 没有解析器负责该组合时（错误码 {@code CAPABILITY_NOT_SUPPORTED}，不可重试）
     */
    public static IConfigResolver forProvider(ModelProviderEnum provider, CapabilityKindEnum kind) {
        for (IConfigResolver resolver : RESOLVERS) {
            if (resolver.supports(provider, kind)) {
                return resolver;
            }
        }
        throw ExceptionUtils.fail(ErrorCodes.CAPABILITY_NOT_SUPPORTED, ErrorCategory.CONFIGURATION, false, provider);
    }

    /**
     * 解析平台配置中的一项能力。
     *
     * @param provider 已加载的平台配置，不可为 null
     * @param kind     能力种类，不可为 null
     * @return 具体配置对象，非 null（静态类型为 {@link ModelConfig}，调用方按需强转为平台派生类）
     * @throws IllegalArgumentException 平台配置为 null、该能力未配置或必填参数不合法时
     * @throws ClaraException           该平台不支持该能力种类时（错误码 {@code CAPABILITY_NOT_SUPPORTED}，不可重试）
     */
    public static ModelConfig resolve(ProviderConfiguration provider, CapabilityKindEnum kind) {
        if (provider == null) {
            throw new IllegalArgumentException("provider configuration must not be null");
        }
        return forProvider(provider.getProvider(), kind).resolve(provider, kind);
    }

    /**
     * 校验注册表中不存在重复覆盖同一「平台 × 能力种类」组合的实现。
     *
     * @throws IllegalStateException 同一组合被多个 resolver 声明时
     */
    private static void validateUniqueRegistration() {
        for (ModelProviderEnum provider : ModelProviderEnum.values()) {
            for (CapabilityKindEnum kind : CapabilityKindEnum.values()) {
                int matches = 0;
                for (IConfigResolver resolver : RESOLVERS) {
                    if (resolver.supports(provider, kind)) {
                        matches++;
                    }
                }
                if (matches > 1) {
                    throw new IllegalStateException("duplicate model config resolver for " + provider + " / " + kind);
                }
            }
        }
    }
}
