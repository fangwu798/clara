package com.clara.model.utils;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 提供严格模式下的 JSON 节点读取与校验工具。
 *
 * <p>集中处理通用的字段读取、未知字段拒绝、安全 URI 校验与非法配置异常构造，供各配置加载器与协议编解码器
 * 复用，使调用方只需关注具体业务映射。
 *
 * <p>读取方法分两类，取值的宽严程度不同，按场景选择：
 * <ul>
 *   <li>配置解析：{@link #requiredText(JsonNode, String)}、{@link #optionalText(JsonNode, String)} 会拒绝
 *       空白值，类型不符抛 {@link IllegalArgumentException}；</li>
 *   <li>协议解析：{@link #textOrNull(JsonNode)} 宽松（类型不符按缺失处理），
 *       {@link #strictTextOrNull(JsonNode)} 严格（类型不符直接报错），两者都允许空串。</li>
 * </ul>
 */
public final class JsonNodeUtils {
    /**
     * 读取必填的对象字段。
     *
     * @param parent 父节点
     * @param field  字段名
     * @return 对象子节点
     * @throws IllegalArgumentException 字段缺失或不是对象时
     */
    public static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw invalidConfig(field + " must be an object", null);
        }
        return value;
    }

    /**
     * 读取必填的非空字符串字段。
     *
     * @param parent 父节点
     * @param field  字段名
     * @return 非空字符串值
     * @throws IllegalArgumentException 字段缺失或为空白时
     */
    public static String requiredText(JsonNode parent, String field) {
        String value = optionalText(parent, field);
        if (value == null) {
            throw invalidConfig(field + " must be a non-blank string", null);
        }
        return value;
    }

    /**
     * 读取可选的字符串字段。
     *
     * @param parent 父节点
     * @param field  字段名
     * @return 字符串值，字段缺失或为 null 时返回 null
     * @throws IllegalArgumentException 字段存在但为空白时
     */
    public static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().trim().isEmpty()) {
            throw invalidConfig(field + " must be a non-blank string", null);
        }
        return value.textValue();
    }

    /**
     * 读取节点自身的文本值（宽松模式）。
     *
     * <p>与 {@link #optionalText(JsonNode, String)} 的区别：本方法不做任何校验，节点为 null、NullNode
     * 或类型不是文本时一律返回 null，适用于读取可选的元数据字段，由调用方自行决定缺失是否可接受。
     *
     * @param node 待读取的节点，可为 null
     * @return 文本值；节点为 null、NullNode 或非文本时返回 null
     */
    public static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    /**
     * 读取节点自身的文本值（严格模式）。
     *
     * <p>与 {@link #textOrNull(JsonNode)} 的区别：节点存在但类型不是文本时直接报错，而不是宽松地按缺失处理，
     * 适合协议解析——字段类型不符属于数据非法，不能静默吞掉。节点为 null 或 NullNode 时仍返回 null，
     * 由调用方决定缺失是否可接受；空串属于合法文本，原样返回，因此与面向配置、拒绝空白值的
     * {@link #optionalText(JsonNode, String)} 不同。
     *
     * @param node 待读取的节点，可为 null
     * @return 文本值；节点为 null 或 NullNode 时返回 null
     * @throws IllegalArgumentException 节点存在但不是文本时
     */
    public static String strictTextOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalidConfig("value must be a text", null);
        }
        return node.textValue();
    }

    /**
     * 读取指定字段的文本值（严格模式）。
     *
     * <p>等价于 {@code strictTextOrNull(parent.get(field))}：字段缺失或为 null 时返回 null，
     * 字段存在但不是文本时报错。
     *
     * @param parent 父节点
     * @param field  字段名
     * @return 字段文本值；字段缺失或为 null 时返回 null
     * @throws IllegalArgumentException 字段存在但不是文本时
     */
    public static String strictTextOrNull(JsonNode parent, String field) {
        return strictTextOrNull(parent.get(field));
    }

    /**
     * 读取必填的整数字段。
     *
     * @param parent 父节点
     * @param field  字段名
     * @return 整数值
     * @throws IllegalArgumentException 字段缺失、非整数或超出 int 范围时
     */
    public static int requiredInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidConfig(field + " must be an integer", null);
        }
        return value.intValue();
    }

    /**
     * 读取整数字段（宽松模式），字段缺失或不是 int 范围内的整数时返回 0。
     *
     * <p>与 {@link #requiredInt(JsonNode, String)} 的区别：本方法不校验、不抛异常，适用于可选的计数类
     * 字段，由调用方自行决定 0 与“缺失”是否等价。
     *
     * @param parent 父节点
     * @param field  字段名
     * @return 整数值；字段缺失、非整数或超出 int 范围时返回 0
     */
    public static int getIntValue(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return 0;
        }
        return value.intValue();
    }

    /**
     * 读取可选的整数字段，区分「缺失」与「值为 0」。
     *
     * <p>与 {@link #getIntValue(JsonNode, String)} 的区别：字段缺失、非整数或超出 int 范围时返回 null，
     * 适用于“平台未提供时应当推导或忽略”的计数类字段（如 token 用量里的总量与缓存命中量），
     * 避免把缺失当成 0 而丢掉上层推导逻辑。
     *
     * @param parent 父节点
     * @param field  字段名
     * @return 整数值；字段缺失、非整数或超出 int 范围时返回 null
     */
    public static Integer optionalIntValue(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return null;
        }
        return Integer.valueOf(value.intValue());
    }

    /**
     * 读取可选的布尔字段。
     *
     * @param parent   父节点
     * @param field    字段名
     * @param fallback 字段缺失或为 null 时返回的默认值
     * @return 布尔值或默认值
     * @throws IllegalArgumentException 字段存在但类型不符时
     */
    public static boolean optionalBoolean(JsonNode parent, String field, boolean fallback) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw invalidConfig(field + " must be a boolean", null);
        }
        return value.booleanValue();
    }

    /**
     * 读取可选的 URI 字段。
     *
     * @param parent 父节点
     * @param field  字段名
     * @return URI，字段缺失时返回 null
     */
    public static URI optionalUri(JsonNode parent, String field) {
        String value = optionalText(parent, field);
        return value == null ? null : safeHttpUri(value, field);
    }

    /**
     * 解析并校验为不含用户信息的安全绝对 HTTP/HTTPS URI。
     *
     * @param value 原始 URI 字符串
     * @param field 字段名，用于错误提示
     * @return 校验通过的 URI
     * @throws IllegalArgumentException 语法非法、非绝对、无主机、含用户信息或协议不符时
     */
    public static URI safeHttpUri(String value, String field) {
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw invalidConfig(field + " must be a safe absolute HTTP URI", null);
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw invalidConfig(field + " is not a valid URI", exception);
        }
    }

    /**
     * 拒绝节点中未声明的字段。
     *
     * @param node    待校验节点
     * @param allowed 允许的字段集合
     * @param section 节点名称，用于错误提示
     * @throws IllegalArgumentException 存在未知字段时
     */
    public static void rejectUnknown(JsonNode node, Set<String> allowed, String section) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw invalidConfig("unknown field in " + section + ": " + name, null);
            }
        }
    }

    /**
     * 构造只读的字段名集合。
     *
     * @param names 字段名数组
     * @return 只读字段名集合
     */
    public static Set<String> fieldSet(String... names) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(names)));
    }

    /**
     * 构造配置非法的异常。
     *
     * @param message 错误消息
     * @param cause   原始异常，可为 null
     * @return 配置非法的异常实例
     */
    public static IllegalArgumentException invalidConfig(String message, Throwable cause) {
        return cause == null ? new IllegalArgumentException(message)
                : new IllegalArgumentException(message, cause);
    }
}
