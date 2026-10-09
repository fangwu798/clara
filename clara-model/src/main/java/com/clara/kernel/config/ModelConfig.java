package com.clara.kernel.config;

import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.ProtocolEnum;
import com.clara.utils.StringUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.net.URI;

/**
 * 能力配置：描述一次能力调用所需的固定信息（平台、能力种类、端点、模型、协议档位与凭据引用）。
 *
 * <p>是什么、在哪一层：配置层（{@code com.clara.kernel.config}）的不可变值对象，也是配置解析的产出类型
 * ——{@link com.clara.kernel.config.resolver.IConfigResolver} 把 {@link ProviderConfiguration} 中的能力
 * 节点映射成本类。各能力的差异不用派生类表达：平台与能力种类由本类持有，字段级差异由请求 / 响应对象上的
 * 适配注解按「平台 + 能力种类」声明，因此本类当前没有派生类。
 *
 * <p>使用方法：
 * <ol>
 *   <li>实例不由调用方 new——由 {@link com.clara.kernel.config.resolver.ConfigResolvers} 从已加载的
 *       {@code ProviderConfiguration} 派生，通常经 {@code ProviderConfiguration#toConfig(CapabilityKindEnum)}
 *       取得；</li>
 *   <li>只读消费：{@code ProviderRequestEncoder} 用它取平台、能力种类与端点，调用方可另读模型 ID 与协议
 *       档位做展示或校验；</li>
 *   <li>字段全部 final、无连接无缓冲，不需要 {@code close()}，实例可跨线程共享。</li>
 * </ol>
 *
 * <p>失败语义：端点不是安全的绝对 HTTP(S) 地址、模型 ID / 协议档位 / 凭据引用为空白、平台为 null 时，
 * 构造期抛 {@link IllegalArgumentException}；配置问题在启动期暴露，不进入调用期，也不可重试。
 * {@code capabilityEnum} 本类不校验，为空时会在编码阶段被拒绝。
 */
@Getter
@EqualsAndHashCode
public class ModelConfig {
    /**
     * 所属模型平台，非 null。对应 YAML 根键 {@code provider}，决定请求体里的平台特有字段与错误归因。
     */
    private final ModelProviderEnum provider;

    /**
     * 能力种类，取自解析时的能力节点键（{@code capabilities.<能力种类>}）；与 {@link #provider} 一起决定编码
     * 时命中哪些字段适配规则。可为 null——本类不校验，编码器要求非 null。
     */
    private final CapabilityKindEnum capabilityEnum;
    /**
     * 能力调用端点，非 null 且必须是安全的绝对 HTTP(S) 地址（含主机、不含 userinfo/查询串以外的凭据）。
     * 对应 YAML 键 {@code capabilities.<能力种类>.endpoint}，必填。
     */
    private final URI endpoint;
    /**
     * 平台固定模型 ID，非空白。对应 YAML 键 {@code capabilities.<能力种类>.modelId}，必填。
     */
    private final String modelId;
    /**
     * API 协议档位，非空白，用于区分同一平台的不同调用变体（例如 {@code chat-completions-v1}）。
     * 对应 YAML 键 {@code capabilities.<能力种类>.apiProfile}，必填。
     */
    private final String apiProfile;
    /**
     * 调用协议枚举，决定编解码阶段采用哪套协议专属规则，未配置时默认为
     * {@link ProtocolEnum#COMPLETION}。对应 YAML 键 {@code capabilities.<能力种类>.protocol}，可选。
     */
    private final ProtocolEnum protocol;
    /**
     * 凭据引用名，非空白。本类只持有引用，真实密钥由凭据解析器在每次调用时解析，因此日志与异常中不会出现密钥。
     * 对应 YAML 键 {@code credential.secretRef}，必填。
     */
    private final String secretRef;

    /**
     * 创建能力配置并校验必填字段。
     *
     * @param provider       所属平台，不可为 null
     * @param capabilityEnum 能力种类，可为 null（本类不校验，编码阶段会要求非 null）
     * @param endpoint       能力调用端点，不可为 null，且必须为安全的绝对 HTTP(S) 地址
     * @param modelId        固定模型 ID，不可为 null 或空白
     * @param apiProfile     API 协议档位，不可为 null 或空白
     * @param protocol       调用协议枚举，不可为 null（未配置时由调用方传入 {@link ProtocolEnum#COMPLETION}）
     * @param secretRef      凭据引用名，不可为 null 或空白
     * @throws IllegalArgumentException provider 为 null、endpoint 非法，或 modelId / apiProfile / secretRef
     *                                  为 null 或空白时
     */
    public ModelConfig(ModelProviderEnum provider, CapabilityKindEnum capabilityEnum, URI endpoint, String modelId, String apiProfile, ProtocolEnum protocol, String secretRef) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        this.provider = provider;
        this.endpoint = requireHttpEndpoint(endpoint, "endpoint");
        this.modelId = StringUtils.requireNotBlank(modelId, "modelId");
        this.apiProfile = StringUtils.requireNotBlank(apiProfile, "apiProfile");
        this.protocol = protocol == null ? ProtocolEnum.COMPLETION : protocol;
        this.secretRef = StringUtils.requireNotBlank(secretRef, "secretRef");
        this.capabilityEnum = capabilityEnum;
    }

    /**
     * 校验地址为安全的绝对 HTTP(S) URI，构造期用于校验 {@code endpoint}。
     *
     * @param value 待校验地址，不可为 null
     * @param field 字段名，用于错误提示，不可为 null
     * @return 校验通过的地址（原样返回，不做规范化）
     * @throws IllegalArgumentException value 为 null、非绝对、无主机、含 userinfo 或协议不是 http/https 时
     */
    protected static URI requireHttpEndpoint(URI value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        if (!value.isAbsolute() || value.getHost() == null || value.getUserInfo() != null
                || !("https".equalsIgnoreCase(value.getScheme()) || "http".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException(field + " must be a safe absolute HTTP URI");
        }
        return value;
    }
}
