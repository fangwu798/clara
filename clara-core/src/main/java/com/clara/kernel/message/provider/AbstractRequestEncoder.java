package com.clara.utils.deserialize;

import com.clara.annoation.ProtocolField;
import com.clara.annoation.ProviderAdapter;
import com.clara.context.ConvContext;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.api.IRequest;
import com.clara.kernel.api.execute.IRequestEncoder;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.http.RawHttpRequest;
import com.clara.kernel.http.TransportConfig;
import com.clara.utils.ExceptionUtils;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 请求编码器的抽象基类：承载 {@link ProviderAdapter} 注解驱动的字段筛选、序列化与请求头合并等协议无关逻辑，
 * 把「协议差异」（字段名映射、字段值结构转换、平台补充字段）留给子类覆盖。
 *
 * <p>子类按协议实现 {@link #protocolName(Field, String)} 与 {@link #protocolValue(Field, Object)}：
 * Chat Completions 不做协议化覆盖，Responses 用 {@link ProtocolField} 映射字段名并用
 * {@link ResponseInputConverter} 转换消息 / 工具结构。
 */
public abstract class AbstractRequestEncoder implements IRequestEncoder {

    /**
     * 请求体的媒体类型。
     */
    private static final String JSON_CONTENT_TYPE = "application/json";

    /**
     * JSON 映射器，用于把请求体映射序列化为字节。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected AbstractRequestEncoder() {
    }

    @Override
    public RawHttpRequest encode(IRequest request, ProviderConfiguration providerConfiguration) {
        TransportConfig transport = providerConfiguration.getTransport();
        ModelConfig config = providerConfiguration.toConfig(ConvContext.current().getCapabilityKind());
        if (request == null || config == null || config.getCapabilityEnum() == null || transport == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        ModelProviderEnum provider = config.getProvider();
        Map<String, String> authorization = providerConfiguration.authorizationHeaders(config.getCapabilityEnum(), Boolean.TRUE.equals(request.getStream()));
        Map<String, Object> body = bodyOf(request, provider, config.getCapabilityEnum());
        postProcessBody(body, provider, authorization);
        byte[] bodyBytes = serialize(body, provider);
        return new RawHttpRequest("POST", config.getEndpoint(), mergedHeaders(authorization, transport), bodyBytes);
    }

    /**
     * 该协议下字段的线格式名。
     *
     * @param field    当前字段，非 null
     * @param baseName 无协议覆盖时应使用的线格式名，非 null
     * @return 最终线格式名；该协议不支持该字段时返回 null（表示丢弃）
     */
    protected abstract String protocolName(Field field, String baseName);

    /**
     * 该协议下字段值的结构转换。
     *
     * @param field 当前字段，非 null
     * @param value 字段值，可为 null
     * @return 转换后的值
     */
    protected abstract Object protocolValue(Field field, Object value);

    /**
     * 序列化前的请求体补充：用于平台 / 协议特有的顶层字段（如 Codex 模拟字段）。
     *
     * @param body          请求体映射，非 null
     * @param provider      当前平台，非 null
     * @param authorization 已组装的授权请求头，非 null
     */
    protected abstract void postProcessBody(Map<String, Object> body, ModelProviderEnum provider, Map<String, String> authorization);

    /**
     * 把请求对象的字段按适配规则收敛为请求体映射。
     */
    protected Map<String, Object> bodyOf(IRequest request, ModelProviderEnum provider, CapabilityKindEnum capability) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        for (Class<?> type : hierarchyOf(request.getClass())) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                put(body, field, request, provider, capability);
            }
        }
        return body;
    }

    /**
     * 计算继承链上的所有类型，顺序为「最上层父类在前、具体类在后」。
     */
    private static List<Class<?>> hierarchyOf(Class<?> type) {
        List<Class<?>> hierarchy = new ArrayList<Class<?>>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            hierarchy.add(0, current);
        }
        return hierarchy;
    }

    /**
     * 按规则决定单个字段是否写入请求体，以及写入什么值。
     */
    protected void put(Map<String, Object> body, Field field, IRequest request, ModelProviderEnum provider, CapabilityKindEnum capability) {
        Object value = read(field, request);
        ProviderAdapter[] adapters = field.getDeclaredAnnotationsByType(ProviderAdapter.class);
        if (adapters.length == 0) {
            String name = protocolName(field, nameOf(field));
            if (name != null) {
                write(body, name, protocolValue(field, value));
            }
            return;
        }
        ProviderAdapter adapter = match(adapters, provider, capability);
        if (adapter == null || !adapter.supported()) {
            return;
        }
        if (adapter.streamingOnly() && !Boolean.TRUE.equals(request.getStream())) {
            return;
        }
        String name = protocolName(field, adapter.name().isEmpty() ? nameOf(field) : adapter.name());
        if (name == null) {
            return;
        }
        if (value != null) {
            write(body, name, protocolValue(field, value));
            return;
        }
        Object fallback = defaultValue(field, adapter);
        if (fallback != null) {
            write(body, name, protocolValue(field, fallback));
        }
    }

    /**
     * 把字段值写入请求体，按线格式名决定是平铺还是嵌套。
     */
    @SuppressWarnings("unchecked")
    private static void write(Map<String, Object> body, String name, Object value) {
        int dot = name.indexOf('.');
        if (dot < 0) {
            body.put(name, value);
            return;
        }
        String head = name.substring(0, dot);
        Object child = body.get(head);
        Map<String, Object> nested;
        if (child instanceof Map) {
            nested = (Map<String, Object>) child;
        } else {
            nested = new LinkedHashMap<String, Object>();
            body.put(head, nested);
        }
        write(nested, name.substring(dot + 1), value);
    }

    /**
     * 在字段的适配规则中找出当前平台与使用类型对应的那一条。
     */
    private static ProviderAdapter match(ProviderAdapter[] adapters, ModelProviderEnum provider, CapabilityKindEnum capability) {
        for (ProviderAdapter adapter : adapters) {
            if (adapter.type() == provider && adapter.capability() == capability) {
                return adapter;
            }
        }
        return null;
    }

    /**
     * 取字段的线格式名。
     */
    private static String nameOf(Field field) {
        JsonProperty property = field.getAnnotation(JsonProperty.class);
        return property == null || property.value().isEmpty() ? field.getName() : property.value();
    }

    /**
     * 读取字段值。
     */
    private static Object read(Field field, IRequest request) {
        try {
            field.setAccessible(true);
            return field.get(request);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("cannot read request field: " + field.getName(), exception);
        }
    }

    /**
     * 把注解里的默认值字面量还原成字段类型的值。
     */
    private static Object defaultValue(Field field, ProviderAdapter adapter) {
        String literal = adapter.defaultValue();
        if (literal.isEmpty()) {
            return null;
        }
        Class<?> type = field.getType();
        if (type == String.class) {
            return literal;
        }
        if (type == Boolean.class || type == Boolean.TYPE) {
            if ("true".equalsIgnoreCase(literal)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(literal)) {
                return Boolean.FALSE;
            }
            throw invalidDefault(field, literal, "boolean");
        }
        if (type == Integer.class || type == Integer.TYPE) {
            return integerValue(field, literal);
        }
        if (type == Long.class || type == Long.TYPE) {
            return longValue(field, literal);
        }
        if (type == Double.class || type == Double.TYPE) {
            return doubleValue(field, literal);
        }
        if (type == Float.class || type == Float.TYPE) {
            return floatValue(field, literal);
        }
        if (type.isEnum()) {
            return enumValue(field, literal, type);
        }
        return null;
    }

    private static Object integerValue(Field field, String literal) {
        try {
            return Integer.valueOf(literal);
        } catch (NumberFormatException exception) {
            throw invalidDefault(field, literal, "integer");
        }
    }

    private static Object longValue(Field field, String literal) {
        try {
            return Long.valueOf(literal);
        } catch (NumberFormatException exception) {
            throw invalidDefault(field, literal, "long");
        }
    }

    private static Object doubleValue(Field field, String literal) {
        try {
            return Double.valueOf(literal);
        } catch (NumberFormatException exception) {
            throw invalidDefault(field, literal, "double");
        }
    }

    private static Object floatValue(Field field, String literal) {
        try {
            return Float.valueOf(literal);
        } catch (NumberFormatException exception) {
            throw invalidDefault(field, literal, "float");
        }
    }

    private static Object enumValue(Field field, String literal, Class<?> type) {
        Object[] constants = type.getEnumConstants();
        for (Object constant : constants) {
            if (((Enum<?>) constant).name().equals(literal)) {
                return constant;
            }
        }
        for (Object constant : constants) {
            if (((Enum<?>) constant).name().equalsIgnoreCase(literal)) {
                return constant;
            }
        }
        throw invalidDefault(field, literal, type.getSimpleName());
    }

    private static IllegalArgumentException invalidDefault(Field field, String literal, String type) {
        return new IllegalArgumentException(
                "default value \"" + literal + "\" is not a valid " + type + " for field " + field.getName());
    }

    /**
     * 合并平台级默认请求头与调用方请求头，并补缺省的内容类型。
     */
    private static Map<String, String> mergedHeaders(Map<String, String> headers, TransportConfig transport) {
        Map<String, String> merged = new LinkedHashMap<String, String>();
        merged.putAll(transport.getHeaders());
        if (headers != null) {
            merged.putAll(headers);
        }
        if (!hasHeaderNamed(merged, "Content-Type")) {
            merged.put("Content-Type", JSON_CONTENT_TYPE);
        }
        if (hasHeaderNamed(merged, "x-Transfer")) {
            merged.remove("x-Transfer");
        }
        return merged;
    }

    private static boolean hasHeaderNamed(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把请求体映射序列化为 UTF-8 字节。
     */
    private static byte[] serialize(Map<String, Object> body, ModelProviderEnum provider) {
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            throw ExceptionUtils.protocolError(provider);
        }
    }
}
