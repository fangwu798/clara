package com.clara.kernel.config;

import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.utils.StringUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.net.URI;

/**
 * 能力配置的公共基类：描述一次能力调用所需的固定信息。
 *
 * <p>用途与位置：本类属于实现层的配置继承树根节点（包 {@code com.clara.model.configuration.config}）。
 * 它只承载所有能力共有的六个字段——平台、端点、模型 ID、协议档位、凭据引用名与生效的请求日志设置；各能力
 * 特有的限制由
 * 补充，平台专有字段再由派生类补充。模型实现类（如 {@code OpenAiCompatibleChatModel}）只读地消费它。
 *
 * <p>使用方法：
 * <ol>
 *   <li>实例不由调用方 new——由 {@code com.clara.kernel.config.resolver} 下的
 *       {@code IConfigResolver} 从已加载的 {@code ProviderConfiguration} 派生，通常经
 *       {@code ProviderConfiguration#toConfig(CapabilityKindEnum)} 取得；</li>
 *   <li>把取得的实例交给平台模型实现的构造器（如
 *       {@code new OpenAiCompatibleChatModel(config, codec, transport, credentials)}）；</li>
 *   <li>只读使用：字段全部 final、无连接无缓冲，不需要 {@code close()}，实例可跨线程共享。</li>
 * </ol>
 *
 * <p>失败语义：端点不是安全的绝对 HTTP(S) 地址、模型 ID / 协议档位 / 凭据引用为空白、平台为 null 时，
 * 构造期抛 {@link IllegalArgumentException}；配置问题在启动期暴露，不进入调用期，也不可重试。
 */
@Getter
@EqualsAndHashCode
public class ModelConfig {
    /**
     * 所属模型平台，非 null。对应 YAML 根键 {@code provider}，决定请求体里的平台特有字段与错误归因。
     */
    private final ModelProviderEnum provider;

    /**
     * 模型类型
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
     * 凭据引用名，非空白。本类只持有引用，真实密钥由凭据解析器在每次调用时解析，因此日志与异常中不会出现密钥。
     * 对应 YAML 键 {@code credential.secretRef}，必填。
     */
    private final String secretRef;

    /**
     * 创建能力配置公共部分并校验必填字段。
     *
     * @param provider       所属平台，不可为 null
     * @param capabilityEnum
     * @param endpoint       能力调用端点，不可为 null，且必须为安全的绝对 HTTP(S) 地址
     * @param modelId        固定模型 ID，不可为 null 或空白
     * @param apiProfile     API 协议档位，不可为 null 或空白
     * @param secretRef      凭据引用名，不可为 null 或空白
     * @throws IllegalArgumentException 任一字段为 null/空白，或端点不是安全的绝对 HTTP(S) 地址时
     */
    public ModelConfig(ModelProviderEnum provider, CapabilityKindEnum capabilityEnum, URI endpoint, String modelId, String apiProfile, String secretRef) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        this.provider = provider;
        this.endpoint = requireHttpEndpoint(endpoint, "endpoint");
        this.modelId = StringUtils.requireNotBlank(modelId, "modelId");
        this.apiProfile = StringUtils.requireNotBlank(apiProfile, "apiProfile");
        this.secretRef = StringUtils.requireNotBlank(secretRef, "secretRef");
        this.capabilityEnum = capabilityEnum;
    }

    /**
     * 校验地址字段为安全的绝对 HTTP(S) URI，供子类校验异步任务查询端点等可选地址复用。
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
