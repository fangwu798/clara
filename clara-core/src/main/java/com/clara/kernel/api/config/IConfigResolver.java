package com.clara.kernel.api.config;

import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.config.resolver.ConfigResolvers;


/**
 * 能力配置解析契约：把 YAML 能力节点映射为具体的 {@link ModelConfig}。
 *
 * <p>用途与位置：配置解析 SPI，实现位于 {@code com.clara.kernel.config.resolver}（当前唯一实现为
 * {@link com.clara.kernel.config.resolver.CompatibleConfigResolver}），只被 {@link ConfigResolvers}
 * 注册表使用；{@code ProviderConfiguration#toConfig(CapabilityKindEnum)} 也是经注册表间接调它。
 * 每个实现负责一组「平台 × 能力种类」组合（例如 OpenAI 兼容家族的对话与图片），
 * 组合之间不得重叠——{@link ConfigResolvers} 在类初始化时校验唯一性，重复注册直接失败。
 *
 * <p>实现方必须保证的语义：
 * <ol>
 *   <li>{@link #supports(ModelProviderEnum, CapabilityKindEnum)} 是纯函数，不得依赖外部状态，且必须与
 *       {@link #resolve} 的适用范围一致：返回 true 的组合调用 {@code resolve} 必须成功或抛出配置错误；</li>
 *   <li>{@code resolve} 只做映射与构造期校验，不做网络调用、不注册模型、不修改入参；</li>
 *   <li>返回的配置对象不可变，可被多线程共享；实现类本身应无状态，可被并发调用。</li>
 * </ol>
 *
 * <p>调用方按以下顺序使用：先按平台与能力种类确认唯一实现（{@code ConfigResolvers.forProvider}），
 * 再调用一次 {@code resolve}；同一能力种类重复解析会得到内容相等的新实例（{@code equals} 成立）。
 *
 * <p>失败语义：能力节点缺失、必填参数缺失或类型不符时抛 {@link IllegalArgumentException}——
 * 属配置问题，在启动期暴露，不进入调用期；平台与能力种类没有实现时由注册表抛配置类异常。
 */
public interface IConfigResolver {

    /**
     * 判断本实现是否负责该平台与能力种类的组合。
     *
     * @param provider 平台，可为 null（为 null 时必须返回 false）
     * @param kind     能力种类，可为 null（为 null 时必须返回 false）
     * @return true 表示本实现负责该组合，且 {@link #resolve} 必须能处理它
     */
    boolean supports(ModelProviderEnum provider, CapabilityKindEnum kind);

    /**
     * 从平台配置中取出指定能力种类对应的节点并构造具体配置对象。
     *
     * @param provider       已加载的平台配置，不可为 null；其平台必须与 {@link #supports} 匹配
     * @param kind           能力种类，不可为 null；决定取哪个能力节点，以及返回的具体配置类型与参数键名
     * @return 具体配置对象，非 null；当前实现返回 {@link ModelConfig} 本身（平台、能力种类、端点、模型 ID、
     *         协议档位与凭据引用）
     * @throws IllegalArgumentException 该能力未配置、必填参数缺失或类型不符时
     */
    ModelConfig resolve(ProviderConfiguration provider, CapabilityKindEnum kind);
}
