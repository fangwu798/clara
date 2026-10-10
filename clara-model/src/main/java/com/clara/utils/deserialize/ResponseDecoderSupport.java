package com.clara.utils.deserialize;

import com.clara.annoation.ResponseAdapter;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.FinishReasonEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.core.model.IResponse;
import com.clara.utils.ExceptionUtils;
import com.clara.utils.FinishReasonUtils;
import com.clara.utils.JsonNodeUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 响应解码的协议无关工具集合：JSON 路径取值、字段读写、类型转换、实例化与字段遍历。
 *
 * <p>供响应解码器与流式累加器共用，避免两处各写一份。无状态、线程安全。
 */
public final class ResponseDecoderSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ResponseDecoderSupport() {
    }

    /**
     * 按路径取值，支持 {@code a.b[0].c} 下标与 {@code a.b[type=xxx].c} 按 {@code type} 过滤两种形式。
     */
    public static JsonNode valueAt(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            int bracket = segment.indexOf('[');
            if (bracket < 0) {
                current = current.get(segment);
                continue;
            }
            if (!segment.endsWith("]")) {
                return null;
            }
            String name = segment.substring(0, bracket);
            JsonNode array = name.isEmpty() ? current : current.get(name);
            if (array == null || !array.isArray()) {
                return null;
            }
            String index = segment.substring(bracket + 1, segment.length() - 1);
            if (index.startsWith("type=")) {
                current = findByType(array, index.substring("type=".length()));
                continue;
            }
            try {
                current = array.get(Integer.parseInt(index));
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return current;
    }

    /**
     * 在数组里找第一个 {@code type} 字段等于指定值的元素。
     */
    static JsonNode findByType(JsonNode array, String type) {
        for (JsonNode item : array) {
            JsonNode typeNode = item.get("type");
            if (typeNode != null && typeNode.isTextual() && type.equals(typeNode.textValue())) {
                return item;
            }
        }
        return null;
    }

    /**
     * 判断节点是否算「有值」：非空、非 NullNode、非空字符串。
     */
    public static boolean present(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        return !node.isTextual() || !node.textValue().isEmpty();
    }

    /**
     * 解析 JSON 文本为节点树。
     */
    public static JsonNode parse(String data, ModelProviderEnum provider) {
        try {
            return MAPPER.readTree(data);
        } catch (IOException exception) {
            throw ExceptionUtils.protocolError(provider);
        }
    }

    /**
     * 写入字段值。
     */
    public static void write(Field field, Object target, Object value) {
        if (value == null) {
            return;
        }
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("cannot write response field: " + field.getName(), exception);
        }
    }

    /**
     * 读取字段值。
     */
    static Object read(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("cannot read response field: " + field.getName(), exception);
        }
    }

    /**
     * 把文本增量追加到目标字段上。
     */
    public static void appendText(Field field, Object target, String text) {
        Object current = read(field, target);
        write(field, target, current == null ? text : ((String) current) + text);
    }

    /**
     * 按目标类型转换节点值。
     */
    public static Object convert(Type type, JsonNode node, ModelProviderEnum provider) {
        Class<?> raw = type instanceof Class ? (Class<?>) type : null;
        if (raw == String.class) {
            return node.isTextual() ? node.textValue() : node.asText();
        }
        if (raw == Integer.class || raw == Integer.TYPE) {
            if (!node.isIntegralNumber() || !node.canConvertToInt()) {
                throw ExceptionUtils.protocolError(provider);
            }
            return Integer.valueOf(node.intValue());
        }
        if (raw == Long.class || raw == Long.TYPE) {
            if (!node.isIntegralNumber()) {
                throw ExceptionUtils.protocolError(provider);
            }
            return Long.valueOf(node.longValue());
        }
        if (raw == Double.class || raw == Double.TYPE) {
            if (!node.isNumber()) {
                throw ExceptionUtils.protocolError(provider);
            }
            return Double.valueOf(node.doubleValue());
        }
        if (raw == Boolean.class || raw == Boolean.TYPE) {
            if (!node.isBoolean()) {
                throw ExceptionUtils.protocolError(provider);
            }
            return Boolean.valueOf(node.booleanValue());
        }
        if (raw == FinishReasonEnum.class) {
            return FinishReasonUtils.of(JsonNodeUtils.textOrNull(node));
        }
        if (raw != null && raw.isEnum()) {
            String literal = JsonNodeUtils.textOrNull(node);
            for (Object constant : raw.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(literal)) {
                    return constant;
                }
            }
            throw ExceptionUtils.protocolError(provider);
        }
        try {
            return MAPPER.convertValue(node, MAPPER.getTypeFactory().constructType(type));
        } catch (IllegalArgumentException exception) {
            throw ExceptionUtils.protocolError(provider);
        }
    }

    /**
     * 创建响应对象。
     */
    public static <R extends IResponse> R newInstance(Class<R> responseType) {
        try {
            Constructor<R> constructor = responseType.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("response type needs a no-arg constructor: " + responseType.getName(), exception);
        }
    }

    /**
     * 取响应类型及父类的实例字段，父类字段在前。
     */
    public static List<Field> fields(Class<?> type) {
        List<Class<?>> hierarchy = new ArrayList<Class<?>>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            hierarchy.add(0, current);
        }
        List<Field> fields = new ArrayList<Field>();
        for (Class<?> current : hierarchy) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * 在字段的规则里找出当前平台与能力对应的第一条。
     */
    public static ResponseAdapter match(Field field, ModelProviderEnum provider, CapabilityKindEnum capability) {
        for (ResponseAdapter adapter : field.getDeclaredAnnotationsByType(ResponseAdapter.class)) {
            if (adapter.capability() != capability) {
                continue;
            }
            ModelProviderEnum[] types = adapter.type();
            if (types.length == 0) {
                return adapter;
            }
            for (ModelProviderEnum type : types) {
                if (type == provider) {
                    return adapter;
                }
            }
        }
        return null;
    }
}
