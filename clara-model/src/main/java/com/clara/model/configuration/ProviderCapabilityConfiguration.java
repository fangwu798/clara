package com.clara.model.configuration;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
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
public final class ProviderCapabilityConfiguration {
    private final URI endpoint;
    private final URI taskQueryEndpoint;
    private final String modelId;
    private final String apiProfile;
    private final Map<String, Object> parameters;

    public ProviderCapabilityConfiguration(URI endpoint, URI taskQueryEndpoint,
            String modelId, String apiProfile, Map<String, Object> parameters) {
        if (endpoint == null || !endpoint.isAbsolute()) {
            throw new IllegalArgumentException("endpoint must be absolute");
        }
        if (taskQueryEndpoint != null && !taskQueryEndpoint.isAbsolute()) {
            throw new IllegalArgumentException("taskQueryEndpoint must be absolute");
        }
        this.endpoint = endpoint;
        this.taskQueryEndpoint = taskQueryEndpoint;
        this.modelId = blankToNull(modelId);
        this.apiProfile = blankToNull(apiProfile);
        this.parameters = immutableScalars(parameters);
    }

    public URI getEndpoint() {
        return endpoint;
    }

    public URI getTaskQueryEndpoint() {
        return taskQueryEndpoint;
    }

    public String getModelId() {
        return modelId;
    }

    public String getApiProfile() {
        return apiProfile;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public String requireModelId() {
        return required(modelId, "modelId");
    }

    public String requireApiProfile() {
        return required(apiProfile, "apiProfile");
    }

    public URI requireTaskQueryEndpoint() {
        if (taskQueryEndpoint == null) {
            throw new IllegalArgumentException("taskQueryEndpoint must be configured");
        }
        return taskQueryEndpoint;
    }

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

    public boolean requireBoolean(String name) {
        Object value = requireParameter(name);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return ((Boolean) value).booleanValue();
    }

    public String requireString(String name) {
        Object value = requireParameter(name);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return (String) value;
    }

    private Object requireParameter(String name) {
        Object value = parameters.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing capability parameter: " + name);
        }
        return value;
    }

    private static Map<String, Object> immutableScalars(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                throw new IllegalArgumentException("parameter name must not be blank");
            }
            Object value = entry.getValue();
            if (!(value instanceof String) && !(value instanceof Number)
                    && !(value instanceof Boolean)) {
                throw new IllegalArgumentException("capability parameters must be scalar values");
            }
            result.put(entry.getKey(), value);
        }
        return Collections.unmodifiableMap(result);
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }
}
