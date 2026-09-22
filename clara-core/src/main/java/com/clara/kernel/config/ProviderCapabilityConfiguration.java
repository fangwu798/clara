package com.clara.kernel.config;

import com.clara.utils.MapUtils;
import com.clara.utils.StringUtils;
import com.clara.utils.deserialize.SafeHttpUriDeserializer;
import com.clara.utils.deserialize.ScalarMapDeserializer;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Getter;

import java.net.URI;
import java.util.Map;

/**
 * 描述平台内一项模型能力的端点、模型 ID、协议版本和扩展参数。
 *
 * <p>常规用法：以能力种类名（{@code CHAT}、{@code IMAGE}、{@code VIDEO}、{@code AUDIO_TRANSCRIPTION}、
 * {@code SPEECH}）作为平台配置中 {@code capabilities} 下的键保存，再由类型化转换方法读取。未被现有转换器
 * 消费的标量参数仍可通过 {@link #getParameters()} 或类型化读取方法交给宿主应用。
 *
 * <p>注意：参数只允许字符串、数字和布尔值，不接受嵌套对象或列表。{@code endpoint} 必填；异步任务
 * 可额外配置 {@code taskQueryEndpoint}。生产配置必须固定明确模型 ID，不能依赖含义会变化的别名。
 */
@Getter
public final class ProviderCapabilityConfiguration {
    /**
     * 能力调用端点，必填且必须为绝对 URI。
     */
    private final URI endpoint;
    /**
     * 异步任务的查询端点，同步能力为 null。
     */
    private final URI taskQueryEndpoint;
    /**
     * 固定模型 ID，未配置时为 null。
     */
    private final String modelId;
    /**
     * API 协议档位，用于区分同一平台的不同调用变体，未配置时为 null。
     */
    private final String apiProfile;
    /**
     * 扩展标量参数，只读且不可变。
     */
    private final Map<String, Object> parameters;

    /**
     * 创建能力配置并校验端点与扩展参数，同时声明 YAML 形状（{@code @JsonCreator} + {@code @JsonProperty}）。
     *
     * <p>绑定走本构造器：{@code endpoint} 由 {@link SafeHttpUriDeserializer} 在绑定期完成「绝对、含主机、
     * 无用户信息、仅 HTTP(S)」校验；{@code parameters} 由 {@link ScalarMapDeserializer} 保证只含标量。
     *
     * @param endpoint          能力调用端点（YAML 键 {@code endpoint}，必填），必须为安全的绝对 HTTP(S) URI
     * @param taskQueryEndpoint 异步任务查询端点（YAML 键 {@code taskQueryEndpoint}，可选），同步能力为 null
     * @param modelId           模型 ID（YAML 键 {@code modelId}，可选），空白视为未配置
     * @param apiProfile        API 协议档位（YAML 键 {@code apiProfile}，可选），空白视为未配置
     * @param parameters        扩展标量参数（YAML 键 {@code parameters}，可选），可为 null
     */
    @JsonCreator
    public ProviderCapabilityConfiguration(
            @JsonProperty(value = "endpoint", required = true)
            @JsonDeserialize(using = SafeHttpUriDeserializer.class) URI endpoint,
            @JsonProperty("taskQueryEndpoint")
            @JsonDeserialize(using = SafeHttpUriDeserializer.class) URI taskQueryEndpoint,
            @JsonProperty("modelId") String modelId,
            @JsonProperty("apiProfile") String apiProfile,
            @JsonProperty("parameters")
            @JsonDeserialize(using = ScalarMapDeserializer.class) Map<String, Object> parameters) {
        if (endpoint == null || !endpoint.isAbsolute()) {
            throw new IllegalArgumentException("endpoint must be absolute");
        }
        if (taskQueryEndpoint != null && !taskQueryEndpoint.isAbsolute()) {
            throw new IllegalArgumentException("taskQueryEndpoint must be absolute");
        }
        this.endpoint = endpoint;
        this.taskQueryEndpoint = taskQueryEndpoint;
        this.modelId = StringUtils.blankToNull(modelId);
        this.apiProfile = StringUtils.blankToNull(apiProfile);
        this.parameters = MapUtils.unmodifiableScalarMap(parameters);
    }

    /**
     * 读取模型 ID。
     *
     * @return 非空模型 ID
     * @throws IllegalArgumentException 未配置模型 ID 时
     */
    public String requireModelId() {
        return StringUtils.requireNotBlank(modelId, "modelId");
    }

    /**
     * 读取 API 协议档位。
     *
     * @return 非空 API 协议档位
     * @throws IllegalArgumentException 未配置 API 协议档位时
     */
    public String requireApiProfile() {
        return StringUtils.requireNotBlank(apiProfile, "apiProfile");
    }

    /**
     * 读取异步任务查询端点。
     *
     * @return 非空异步任务查询端点
     * @throws IllegalArgumentException 未配置查询端点时
     */
    public URI requireTaskQueryEndpoint() {
        if (taskQueryEndpoint == null) {
            throw new IllegalArgumentException("taskQueryEndpoint must be configured");
        }
        return taskQueryEndpoint;
    }

    /**
     * 读取指定名称的整数参数。
     *
     * @param name 参数名
     * @return 参数值对应的 int
     * @throws IllegalArgumentException 参数缺失、非数字或超出 int 范围时
     */
    public int requireInt(String name) {
        Object value = requireParameter(name);
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE
                || value instanceof Double && ((Double) value).doubleValue() != number
                || value instanceof Float && ((Float) value).floatValue() != number) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return (int) number;
    }

    /**
     * 读取指定名称的布尔参数。
     *
     * @param name 参数名
     * @return 布尔参数值
     * @throws IllegalArgumentException 参数缺失或类型不符时
     */
    public boolean requireBoolean(String name) {
        Object value = requireParameter(name);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return ((Boolean) value).booleanValue();
    }

    /**
     * 读取指定名称的非空字符串参数。
     *
     * @param name 参数名
     * @return 非空字符串参数值
     * @throws IllegalArgumentException 参数缺失、类型不符或为空白时
     */
    public String requireString(String name) {
        Object value = requireParameter(name);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return (String) value;
    }

    /**
     * 读取指定名称的参数原始值。
     *
     * @param name 参数名
     * @return 参数原始值
     * @throws IllegalArgumentException 参数缺失时
     */
    private Object requireParameter(String name) {
        Object value = parameters.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing capability parameter: " + name);
        }
        return value;
    }
}
