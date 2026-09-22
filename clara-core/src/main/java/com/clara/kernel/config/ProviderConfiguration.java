package com.clara.kernel.config;


import com.clara.context.ConvContext;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.api.credential.ICredentialResolver;
import com.clara.kernel.config.resolver.ConfigResolvers;
import com.clara.kernel.credential.ProviderCredentialConfiguration;
import com.clara.kernel.exception.ClaraException;
import com.clara.kernel.http.TransportConfig;
import com.clara.utils.CodexTuiGenerator;
import com.clara.utils.ExceptionUtils;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.io.Closeable;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表示从单个平台 YAML 文件加载的完整模型配置。
 *
 * <p>是什么、在哪一层：实现层的平台级配置聚合对象，一个实例对应一个 {@link ModelProviderEnum}，
 * 持有凭据配置、传输配置，以及「能力种类 → 能力节点」的只读映射。它不是调用方手工拼装的 DTO，
 *
 * <p>使用方法：
 * <ol>
 *
 *   <li>需要 HTTP 传输时取 {@link #getTransport()} 再调 {@code toHttpTransportConfig()}，
 *       需要密钥时调 {@link #credentialResolver()}，需要一次往返的请求头（鉴权头 + 请求体
 *       {@code Content-Type} + 按流式区分的 {@code Accept}）时调
 *       {@link #authorizationHeaders(CapabilityKindEnum, boolean)}；</li>
 *   <li>按能力种类取得具体配置：
 *       {@code ModelConfig config = providerConfiguration.toConfig(CapabilityKindEnum.CHAT);}
 *       再按需强转为平台派生类（如 {@code OpenAiCompatibleChatConfig}）注入模型实现；
 *       平台专属字段（如方舟图片的 {@code watermark}）由调用方从对应能力节点的参数中读取</li>
 *   <li>本类拥有凭据配置的生命周期，必须在用完后调用 {@link #close()}（推荐 try-with-resources）。</li>
 * </ol>
 *
 * <p>以 DeepSeek 为例：
 * <pre>{@code
 * try (ProviderConfiguration configuration = new ProviderYamlConfigurationLoader().load(Paths.get("config/model-providers/deepseek.yml"))) {
 *     OpenAiCompatibleChatConfig chatConfig =
 *             (OpenAiCompatibleChatConfig) configuration.toConfig(CapabilityKindEnum.CHAT);
 *     OpenAiCompatibleChatModel model = new OpenAiCompatibleChatModel(chatConfig,
 *             new DeepSeekChatCodec(),
 *             new Jdk8HttpTransport(configuration.getTransport().toHttpTransportConfig()),
 *             configuration.credentialResolver());
 *     // 组装 ChatRequest 后调用 model.chat(request)
 * }
 * }</pre>
 *
 * <p>生命周期与线程安全：实例自身不可变（能力映射为只读视图），可跨线程共享；但 {@link #close()}
 * 会清零凭据而影响后续调用，因此应在停止使用该平台后再关闭。
 *
 * <p>失败语义：构造期对 null 与空能力表抛 {@link IllegalArgumentException}；{@link #toConfig(CapabilityKindEnum)}
 * 在该能力未配置或组合不受支持时分别抛 {@link IllegalArgumentException} 与 {@code ClaraException}
 * （错误码 {@code CAPABILITY_NOT_SUPPORTED}，不可重试）。
 */
@Getter
public final class ProviderConfiguration implements Closeable {

    /**
     * 流式调用的期望响应内容类型。
     */
    private static final String EVENT_STREAM_MEDIA_TYPE = "text/event-stream";
    /**
     * JSON 媒体类型：既用作非流式调用的 {@code Accept}，也用作 JSON 请求体的 {@code Content-Type}。
     */
    private static final String JSON_MEDIA_TYPE = "application/json";
    /**
     * Anthropic 平台写入 {@code anthropic-version} 头的参数名（能力参数 {@code parameters.apiVersion}）。
     */
    private static final String API_VERSION_PARAMETER = "apiVersion";

    /**
     * 模型提供平台
     */
    private final ModelProviderEnum provider;
    /**
     * 凭据配置，拥有其生命周期。
     */
    private final ProviderCredentialConfiguration credential;
    /**
     * 平台共用的传输配置。
     */
    private final TransportConfig transport;
    /**
     * 能力种类到能力配置的只读映射。
     */
    private final Map<CapabilityKindEnum, ProviderCapabilityConfiguration> capabilities;

    /**
     * 创建平台配置，同时声明 YAML 形状（{@code @JsonCreator} + {@code @JsonProperty}）。
     *
     * <p>绑定走本构造器，因此下面的校验就是配置加载期的校验：YAML 缺少 {@code provider} /
     * {@code credential} / {@code transport} / {@code capabilities} 任一项时由绑定器报「缺失」，
     * 传入 null 或能力表为空时由本构造器报错。
     *
     * @param provider     所属模型平台，不可为空
     * @param credential   凭据配置，不可为空；本对象拥有其生命周期
     * @param transport    传输配置，不可为空
     * @param capabilities 能力映射，不可为空且不得包含 null
     * @throws IllegalArgumentException 必填项为空或能力映射为空 / 含 null 时（此时凭据配置会被关闭，
     *                                  避免内联密钥明文残留）
     */
    @JsonCreator
    public ProviderConfiguration(
            @JsonProperty(value = "provider", required = true) ModelProviderEnum provider,
            @JsonProperty(value = "credential", required = true) ProviderCredentialConfiguration credential,
            @JsonProperty(value = "transport", required = true) TransportConfig transport,
            @JsonProperty(value = "capabilities", required = true)
            Map<CapabilityKindEnum, ProviderCapabilityConfiguration> capabilities) {
        if (provider == null || credential == null || transport == null) {
            throw new IllegalArgumentException("provider configuration must not contain null");
        }
        this.provider = provider;
        this.credential = credential;
        this.transport = transport;
        this.capabilities = freeze(credential, capabilities);

    }

    /**
     * 冻结能力映射；校验失败时先关闭凭据配置再抛出，避免内联密钥明文残留。
     *
     * @param credential   已构造的凭据配置，不可为 null
     * @param capabilities 能力映射，可为 null
     * @return 只读的能力映射
     * @throws IllegalArgumentException 能力映射为 null、为空或含 null 时
     */
    private static Map<CapabilityKindEnum, ProviderCapabilityConfiguration> freeze(ProviderCredentialConfiguration credential, Map<CapabilityKindEnum, ProviderCapabilityConfiguration> capabilities) {
        try {
            if (capabilities == null || capabilities.isEmpty()) {
                throw new IllegalArgumentException("capabilities must not be empty");
            }
            if (capabilities.containsKey(null) || capabilities.containsValue(null)) {
                throw new IllegalArgumentException("capabilities must not contain null");
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
        } catch (RuntimeException exception) {
            credential.close();
            throw exception;
        }
    }

    /**
     * 按能力种类读取能力配置。
     *
     * @param kind 能力种类
     * @return 对应的能力配置
     * @throws IllegalArgumentException kind 为 null 或该能力未配置时
     */
    public ProviderCapabilityConfiguration getCapability(CapabilityKindEnum kind) {
        ProviderCapabilityConfiguration capability = capabilities.get(kind);
        if (capability == null) {
            throw new IllegalArgumentException("capability is not configured: " + kind);
        }
        return capability;
    }

    /**
     * 创建基于当前凭据配置的凭据解析器。
     *
     * @return 凭据解析器，非 null；返回值与凭据配置共享生命周期，{@link #close()} 后解析会失败
     */
    public ICredentialResolver credentialResolver() {
        return credential.resolver();
    }

    /**
     * 组装一次调用的授权请求头，并按「流式 / 非流式」给出请求体内容类型与期望响应类型的头。
     *
     * <p>为什么把 {@code Content-Type} 与 {@code Accept} 一起返回：它们与鉴权头同属「一次往返固定要带的
     * 头」——{@code Content-Type} 决定请求体怎么被解析，{@code Accept} 决定平台按什么格式返回（流式必须
     * 声明 {@code text/event-stream}，否则平台按非流式返回，传输层会因响应内容类型不符而报协议错误），
     * 放在一起组装可避免各调用方各拼一遍、拼错一处就整次调用失败。
     *
     * <p>鉴权头形状随平台而定：
     * <ul>
     *   <li>Anthropic：{@code x-api-key} 加 {@code anthropic-version}，版本取自能力参数
     *       {@code parameters.apiVersion}（必填）；</li>
     *   <li>OpenAI 兼容家族（OpenAI、火山方舟、DeepSeek、阿里通义）：{@code Authorization: Bearer <密钥>}。</li>
     * </ul>
     *
     * <p>密钥在方法内解析，转成头值后立即把解析出的字符数组清零；但头值本身已含明文密钥，因此返回值
     * <b>不得写入日志或异常信息</b>。
     *
     * @param kind      能力种类，用于读取平台专有头参数（如 Anthropic 的 {@code apiVersion}），不可为 null
     * @param streaming 本次调用是否为流式；决定 {@code Accept} 的值
     * @return 不可修改的请求头映射，非 null，包含鉴权头、{@code Content-Type: application/json} 与
     * {@code Accept}
     * @throws IllegalArgumentException kind 为 null、该能力未配置，或平台专有头参数缺失 / 类型不符时
     * @throws ClaraException           密钥不可用（引用名不匹配或环境变量未设置）时，错误码
     *                                  {@code MODEL_CREDENTIAL_UNAVAILABLE}，配置问题不可重试
     */
    public Map<String, String> authorizationHeaders(CapabilityKindEnum kind, boolean streaming) {
        return authorizationHeaders(kind, streaming, JSON_MEDIA_TYPE);
    }

    /**
     * 组装一次调用的授权请求头，并指定请求体的内容类型。
     *
     * <p>需要指定内容类型的场景：音频转写等以 multipart 上传的调用传 {@code multipart/form-data; boundary=…}；
     * 无请求体的 GET / DELETE 调用传 {@code null}，此时不下发 {@code Content-Type}。
     *
     * @param kind        能力种类，用于读取平台专有头参数，不可为 null
     * @param streaming   本次调用是否为流式；决定 {@code Accept} 的值
     * @param contentType 请求体内容类型，可为 null（表示本次请求不带请求体，不下发该头）
     * @return 不可修改的请求头映射，非 null，包含鉴权头、{@code Accept}，以及 contentType 非 null 时的
     * {@code Content-Type}
     * @throws IllegalArgumentException kind 为 null、该能力未配置，或平台专有头参数缺失 / 类型不符时
     * @throws ClaraException           密钥不可用（引用名不匹配或环境变量未设置）时，错误码
     *                                  {@code MODEL_CREDENTIAL_UNAVAILABLE}，配置问题不可重试
     */
    public Map<String, String> authorizationHeaders(CapabilityKindEnum kind, boolean streaming, String contentType) {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        char[] secret = credential.resolver().resolve(credential.getSecretRef());
        if (secret == null || secret.length == 0) {
            throw ExceptionUtils.credentialUnavailable(provider);
        }
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            if (provider == ModelProviderEnum.ANTHROPIC) {
                headers.put("x-api-key", new String(secret));
                headers.put("anthropic-version", getCapability(kind).requireString(API_VERSION_PARAMETER));
            } else {
                headers.put("Authorization", "Bearer " + new String(secret));
            }
            //调用OPENAI的时候如果使用了中转站的时候 中转站可能禁止API调用需要模拟codex的调用
            if (provider == ModelProviderEnum.OPENAI) {
                Map<String, String> headerConfig = ConvContext.current().getProviderConfiguration().getTransport().getHeaders();
                if (headerConfig.containsKey("x-Transfer")) {
                    CodexTuiGenerator generator = new CodexTuiGenerator();
                    Map<String, String> codexHeaders = generator.buildHeaders();
                    for (String key : codexHeaders.keySet()) {
                        if (!headers.containsKey(key)) {
                            headers.put(key, codexHeaders.get(key));
                        }
                    }
                }

            }
            if (contentType != null) {
                headers.put("Content-Type", contentType);
            }
            headers.put("Accept", streaming ? EVENT_STREAM_MEDIA_TYPE : JSON_MEDIA_TYPE);
            return Collections.unmodifiableMap(headers);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    /**
     * 按能力种类构造具体能力配置。
     *
     * <p>委托 {@link ConfigResolvers} 查找唯一负责该「平台 × 能力种类」组合的解析器，
     * 因此新增平台/能力不影响本类。解析只做映射与构造期校验，不发起任何网络调用。
     *
     * @param kind 能力种类，不可为 null；决定返回的具体配置类型与参数键名
     * @return 具体能力配置，非 null；当前实现返回 {@link ModelConfig} 本身，含平台、能力种类、端点、模型 ID、协议档位与凭据引用
     * @throws IllegalArgumentException 该能力未配置、必填参数缺失或类型不符时
     * @throws ClaraException           该平台不支持该能力种类时（错误码 {@code CAPABILITY_NOT_SUPPORTED}，不可重试）
     */
    public ModelConfig toConfig(CapabilityKindEnum kind) {
        return ConfigResolvers.resolve(this, kind);
    }


    /**
     * 关闭并释放凭据配置。
     */
    @Override
    public void close() {
        credential.close();
    }
}
