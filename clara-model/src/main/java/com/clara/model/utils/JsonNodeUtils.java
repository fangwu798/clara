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
 * <p>集中处理配置解析中通用的字段读取、未知字段拒绝、安全 URI 校验与非法配置异常构造，
 * 供各配置加载器复用，使加载器只需关注具体业务映射。
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
