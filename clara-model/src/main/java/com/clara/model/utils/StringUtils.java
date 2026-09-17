package com.clara.model.utils;

/**
 * 提供字符串校验与处理的公共工具。
 */
public final class StringUtils {
    /**
     * 校验字符串非空并返回原值。
     *
     * @param value 待校验的字符串
     * @param name  字段名，用于错误提示
     * @return 非空字符串原值
     * @throws IllegalArgumentException 字符串为 null 或空白时
     */
    public static String requireNotBlank(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * 判断字符串是否为 null 或空白。
     *
     * @param value 待判定的字符串
     * @return 为 null 或空白时返回 true
     */
    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 将空白字符串转换为 null。
     *
     * @param value 待处理的字符串
     * @return 非空白原值，null 或空白时返回 null
     */
    public static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }
}
