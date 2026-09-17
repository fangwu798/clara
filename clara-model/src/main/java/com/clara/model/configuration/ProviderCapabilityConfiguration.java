package com.clara.model.configuration;

import com.clara.model.utils.MapUtils;
import com.clara.model.utils.StringUtils;
import lombok.Data;

import java.net.URI;
import java.util.Map;

/**
 * 描述平台内一项模型能力的端点、模型 ID、协议版本和扩展参数。
 *
 * <p>常规用法：以 {@code chat}、{@code embedding}、{@code image}、{@code video}、
 * {@code transcription} 等业务名称保存到平台配置中，再由类型化转换方法读取。未被现有转换器消费的
 * 标量参数仍可通过 {@link #getParameters()} 或类型化读取方法交给宿主应用。
 *
 * <p>注意：参数只允许字符串、数字和布尔值，不接受嵌套对象或列表。{@code endpoint} 必填；异步任务
 * 可额外配置 {@code taskQueryEndpoint}。生产配置必须固定明确模型 ID，不能依赖含义会变化的别名。
 */
@Data
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
     * 创建能力配置并校验端点与扩展参数。
     *
     * @param endpoint          能力调用端点，必须为绝对 URI
     * @param taskQueryEndpoint 异步任务查询端点，可为 null
     * @param modelId           模型 ID，空字符串视为未配置
     * @param apiProfile        API 协议档位，空字符串视为未配置
     * @param parameters        扩展标量参数，可为 null
     */
    public ProviderCapabilityConfiguration(URI endpoint, URI taskQueryEndpoint, String modelId, String apiProfile, Map<String, Object> parameters) {
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
