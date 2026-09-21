package com.clara.utils.deserialize;

import com.clara.annoation.ProviderAdapter;
import com.clara.context.ConvContext;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.api.IRequest;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.http.RawHttpRequest;
import com.clara.kernel.http.TransportConfig;
import com.clara.utils.ExceptionUtils;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * 请求编码器：把 {@link IRequest} 派生对象（如 {@code ChatRequest}、承载 {@code ImageParameters} 的图片请求）
 * 按字段上的 {@link ProviderAdapter} 适配规则编码成可直接发送的 {@link RawHttpRequest}。
 *
 * <p>是什么、在哪一层：契约层（{@code com.clara.kernel.http}）的静态工具类。它把「平台差异」从各平台
 * codec 里的手写 if/put 收敛为一处反射规则：字段上的注解声明该字段在「哪个平台 + 哪种使用类型」下
 * 的默认值、是否支持、取值集合与区间，本类只按规则筛选字段并序列化，不解释业务语义。
 *
 * <p>谁创建、谁调用：无状态、构造器私有，只由模型实现（{@code com.clara.model.provider.*} 下的对话 / 图片
 * 模型）在发送前调用；平台与该请求所属能力由调用方从 {@link ModelConfig} 与自身实现确定。
 *
 * <p>字段处理规则（按字段声明顺序，继承链上父类字段在前）：
 * <ol>
 *   <li>遍历运行时类到 {@code Object} 的整条继承链，取每一层的实例字段（静态字段与编译器合成字段除外），
 *       保证父类（{@code ModelParameters} / {@code ImageParameters}）里的字段一并参与；</li>
 *   <li><b>没有</b> {@link ProviderAdapter} 的字段视为与平台无关，一律写入请求体，即使值为 null；</li>
 *   <li><b>有</b> {@link ProviderAdapter} 的字段，只在该注解的 {@code type} 等于当前平台、{@code capability}
 *       等于当前使用类型时保留；没有匹配规则、或匹配到的规则声明 {@code supported = false} 时，该字段
 *       <b>不下发</b>；</li>
 *   <li>匹配上且字段有值：直接使用字段自身的值（注解里的 {@code allowed} / {@code min} / {@code max} /
 *       {@code maxCount} 是声明性约束，本类不做二次校验）；</li>
 *   <li>匹配上但字段为空：使用注解的 {@code defaultValue}——字面量按字段类型还原（字符串、布尔、整型、
 *       长整型、浮点、枚举）；{@code defaultValue} 为空串时丢掉该字段；字段类型无法由字面量还原
 *       （如 {@code Thinking} 这类复合对象）时同样丢掉该字段，交由平台自身默认值处理；</li>
 *   <li>字段名优先取匹配到的 {@link ProviderAdapter#name()}（用于同一字段在不同能力下线格式不同的场景），
 *       其次取 {@link JsonProperty} 声明的线格式名，都未声明时用字段名；线格式名里可以带点号表示嵌套层级
 *       （如 {@code response_format.type}、{@code optimize_prompt_options.mode}），编码器会据此组装嵌套对象。</li>
 * </ol>
 *
 * <p>请求头：先放入 {@link TransportConfig#getHeaders()} 的平台级默认头，再用调用方传入的 {@code headers}
 * 覆盖同名项（同名判断忽略大小写），最后在缺失时补 {@code Content-Type: application/json}。
 *
 * <p>生命周期与线程安全：本类无状态，内部 {@link ObjectMapper} 线程安全，可被多线程并发调用。
 *
 * <p>失败语义：{@code request}、{@code config}、{@code capability}、{@code transport} 为 null，或注解里
 * {@code defaultValue} 的字面量与字段类型不匹配（例如布尔字段写成 {@code yes}）时抛
 * {@link IllegalArgumentException}，属调用方或注解声明错误，不可重试；请求体序列化失败时抛
 * {@code ClaraException}（{@code MODEL_PROTOCOL_ERROR}，归因到当前平台）。
 */
public final class ProviderRequestEncoder {

    /**
     * 请求体的媒体类型。
     */
    private static final String JSON_CONTENT_TYPE = "application/json";

    /**
     * JSON 映射器，用于把请求体映射序列化为字节。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProviderRequestEncoder() {
    }

    /**
     * 按平台适配规则编码一次能力调用。
     *
     * <p>编码结果是 POST 请求：端点取 {@code config} 的能力端点，请求体是筛选后的参数字节。
     * 调用方提供 Authorization 等业务请求头，本类只补缺省的 {@code Content-Type}。
     *
     * @param request               请求对象（{@link IRequest} 派生类，如 {@code ChatRequest}），不可为 null
     * @param providerConfiguration 能力配置，提供平台（决定注解匹配）与端点，不可为 null
     * @return 可直接交给传输实现的 HTTP 请求，非 null
     * @throws IllegalArgumentException                  任一必需入参为 null，或注解默认值字面量与字段类型不匹配时
     * @throws com.clara.kernel.exception.ClaraException 请求体无法序列化时
     */
    public static RawHttpRequest encode(IRequest request, ProviderConfiguration providerConfiguration) {
        TransportConfig transport = providerConfiguration.getTransport();
        ModelConfig config = providerConfiguration.toConfig(ConvContext.current().getCapabilityKind());
        if (request == null || config == null || config.getCapabilityEnum() == null || transport == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        ModelProviderEnum provider = config.getProvider();
        Map<String, String> authorization =
                providerConfiguration.authorizationHeaders(config.getCapabilityEnum(), Boolean.TRUE.equals(request.getStream()));
        byte[] body = serialize(bodyOf(request, provider, config.getCapabilityEnum()), provider);
        return new RawHttpRequest("POST", config.getEndpoint(), mergedHeaders(authorization, transport), body);
    }

    /**
     * 把请求对象的字段按适配规则收敛为请求体映射。
     *
     * @param request    请求对象，非 null
     * @param provider   当前平台，非 null
     * @param capability 当前使用类型，非 null
     * @return 保留顺序的请求体映射，非 null
     */
    private static Map<String, Object> bodyOf(IRequest request, ModelProviderEnum provider, CapabilityKindEnum capability) {
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
     * 计算继承链上的所有类型，顺序为「最上层父类在前、具体类在后」，使子类同名字段覆盖父类。
     *
     * @param type 请求对象的运行时类型，非 null
     * @return 类型列表，非 null；不含 {@code Object}
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
     *
     * @param body       请求体映射，非 null
     * @param field      当前字段，非 null
     * @param request    请求对象，非 null
     * @param provider   当前平台，非 null
     * @param capability 当前使用类型，非 null
     * @throws IllegalArgumentException 注解默认值字面量与字段类型不匹配时
     */
    private static void put(Map<String, Object> body, Field field, IRequest request, ModelProviderEnum provider, CapabilityKindEnum capability) {
        Object value = read(field, request);
        ProviderAdapter[] adapters = field.getDeclaredAnnotationsByType(ProviderAdapter.class);
        if (adapters.length == 0) {
            write(body, nameOf(field), value);
            return;
        }
        ProviderAdapter adapter = match(adapters, provider, capability);
        if (adapter == null || !adapter.supported()) {
            return;
        }
        if (adapter.streamingOnly() && !Boolean.TRUE.equals(request.getStream())) {
            return;
        }
        String name = adapter.name().isEmpty() ? nameOf(field) : adapter.name();
        if (value != null) {
            write(body, name, value);
            return;
        }
        Object fallback = defaultValue(field, adapter);
        if (fallback != null) {
            write(body, name, fallback);
        }
    }

    /**
     * 把字段值写入请求体，按线格式名决定是平铺还是嵌套。
     *
     * <p>线格式名里可以带点号表示嵌套层级（如 {@code optimize_prompt_options.mode}）：平台把若干参数收在
     * 一个对象里时只能这样声明，编码器不做平台分支，层级完全由名字表达。
     *
     * @param body  请求体映射，非 null
     * @param name  线格式名，非 null；含点号时按层级嵌套
     * @param value 字段值，可为 null
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
     *
     * @param adapters   字段上的全部适配规则，非 null
     * @param provider   当前平台，非 null
     * @param capability 当前使用类型，非 null
     * @return 匹配到的规则；没有匹配时返回 null
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
     *
     * @param field 当前字段，非 null
     * @return {@link JsonProperty} 声明的名字；未声明时返回字段名
     */
    private static String nameOf(Field field) {
        JsonProperty property = field.getAnnotation(JsonProperty.class);
        return property == null || property.value().isEmpty() ? field.getName() : property.value();
    }

    /**
     * 读取字段值。
     *
     * @param field   当前字段，非 null
     * @param request 请求对象，非 null
     * @return 字段值，可为 null
     * @throws IllegalStateException 字段不可访问时
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
     *
     * @param field   当前字段，非 null
     * @param adapter 匹配到的适配规则，非 null
     * @return 还原后的默认值；无默认值或字段类型无法由字面量还原时返回 null（表示该字段不下发）
     * @throws IllegalArgumentException 字面量与字段类型不匹配时
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

    /**
     * 把字面量还原为整型。
     *
     * @param field   当前字段，非 null
     * @param literal 字面量，非 null
     * @return 整型取值
     * @throws IllegalArgumentException 字面量不是整数时
     */
    private static Object integerValue(Field field, String literal) {
        try {
            return Integer.valueOf(literal);
        } catch (NumberFormatException exception) {
            throw invalidDefault(field, literal, "integer");
        }
    }

    /**
     * 把字面量还原为长整型。
     *
     * @param field   当前字段，非 null
     * @param literal 字面量，非 null
     * @return 长整型取值
     * @throws IllegalArgumentException 字面量不是整数时
     */
    private static Object longValue(Field field, String literal) {
        try {
            return Long.valueOf(literal);
        } catch (NumberFormatException exception) {
            throw invalidDefault(field, literal, "long");
        }
    }

    /**
     * 把字面量还原为双精度浮点。
     *
     * @param field   当前字段，非 null
     * @param literal 字面量，非 null
     * @return 浮点取值
     * @throws IllegalArgumentException 字面量不是数值时
     */
    private static Object doubleValue(Field field, String literal) {
        try {
            return Double.valueOf(literal);
        } catch (NumberFormatException exception) {
            throw invalidDefault(field, literal, "double");
        }
    }

    /**
     * 把字面量还原为单精度浮点。
     *
     * @param field   当前字段，非 null
     * @param literal 字面量，非 null
     * @return 浮点取值
     * @throws IllegalArgumentException 字面量不是数值时
     */
    private static Object floatValue(Field field, String literal) {
        try {
            return Float.valueOf(literal);
        } catch (NumberFormatException exception) {
            throw invalidDefault(field, literal, "float");
        }
    }

    /**
     * 把字面量还原为枚举常量，先按常量名精确匹配、再按忽略大小写匹配。
     *
     * @param field   当前字段，非 null
     * @param literal 字面量，非 null
     * @param type    枚举类型，非 null
     * @return 匹配到的枚举常量
     * @throws IllegalArgumentException 没有同名常量时
     */
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

    /**
     * 构造「注解默认值与字段类型不匹配」的参数错误。
     *
     * @param field   当前字段，非 null
     * @param literal 字面量，非 null
     * @param type    期望的类型描述，非 null
     * @return 待抛出的异常
     */
    private static IllegalArgumentException invalidDefault(Field field, String literal, String type) {
        return new IllegalArgumentException(
                "default value \"" + literal + "\" is not a valid " + type + " for field " + field.getName());
    }

    /**
     * 合并平台级默认请求头与调用方请求头，并补缺省的内容类型。
     *
     * <p>同名头忽略大小写，以调用方传入的为准。
     *
     * @param headers   调用方请求头，可为 null
     * @param transport 传输配置，非 null
     * @return 待传给 {@link RawHttpRequest} 的请求头，非 null
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
        return merged;
    }

    /**
     * 判断请求头集合中是否已有指定名字的头（忽略大小写）。
     *
     * @param headers 请求头集合，非 null
     * @param name    头名，非 null
     * @return 已存在时返回 true
     */
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
     *
     * @param body     请求体映射，非 null
     * @param provider 当前平台，用于错误归因，非 null
     * @return 请求体字节，非 null
     * @throws com.clara.kernel.exception.ClaraException 序列化失败时
     */
    private static byte[] serialize(Map<String, Object> body, ModelProviderEnum provider) {
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            throw ExceptionUtils.protocolError(provider);
        }
    }
}
