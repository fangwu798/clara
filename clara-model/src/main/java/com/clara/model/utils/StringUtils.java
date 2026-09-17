package com.clara.model.utils;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 提供字符串校验、处理与编码转换的公共工具。
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

    /**
     * 计算字符串按 UTF-8 编码后的字节长度。
     *
     * <p>用于以字节为单位的大小限制校验，例如 SSE 的单行与单事件体积统计。
     *
     * @param value 待计算的字符串，不可为 null
     * @return UTF-8 编码后的字节数
     */
    public static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 以严格 UTF-8 解码字节数组。
     *
     * <p>严格指遇到非法或不完整字节序列时直接报错，而不是替换为 U+FFFD，便于调用方按协议错误处理。
     *
     * @param bytes 待解码的字节数组
     * @return 解码后的字符串
     * @throws CharacterCodingException 存在非法或不完整字节序列时
     */
    public static String decodeUtf8Strict(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }
}
