package com.clara.utils;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 字符串校验、处理与 UTF-8 编码转换的公共工具。
 *
 * <p>定位：无状态的纯函数集合，不依赖任何业务类型，供全仓任意模块引用。
 *
 * <p>使用方法：
 * <ul>
 *   <li>无需实例化——构造器私有，全部方法为 {@code static}；</li>
 *   <li>构造期校验用 {@link #requireNotBlank(String, String)}；只想判空用 {@link #isBlank(String)}；
 *       要把「空白」与「未提供」归一为 null 用 {@link #blankToNull(String)}；
 *       要区分「未出现」与「出现但为空」（如流式累积缓冲）用 {@link #emptyToNull(CharSequence)}；</li>
 *   <li>按字节体积限制校验（SSE 单行 / 单事件）用 {@link #utf8Length(String)}；
 *       协议解码用 {@link #decodeUtf8Strict(byte[])}；</li>
 *   <li>控制台多列排版用 {@link #displayWidth(String)} 与 {@link #padRight(String, int)}：
 *       按「东亚全角占两列」计算宽度，中英混排也能对齐。</li>
 * </ul>
 *
 * <p>线程安全：无状态、无可变字段，可跨线程并发调用。
 *
 * <p>失败语义：仅 {@link #requireNotBlank(String, String)} 抛 {@link IllegalArgumentException}、
 * {@link #decodeUtf8Strict(byte[])} 抛 {@link CharacterCodingException}；其余方法不抛异常，
 * 一律用 null 表达「无值」。
 */
public final class StringUtils {

    private StringUtils() {
    }

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
     * @param value 待判定的字符串，可为 null
     * @return 为 null 或去除首尾空白后为空串时返回 true
     */
    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 判断字符串是否包含非空白字符。
     *
     * @param value 待判断字符串，可为 null
     * @return 非 null 且包含至少一个非空白字符时返回 true
     */
    public static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    /**
     * 将空白字符串转换为 null。
     *
     * @param value 待处理的字符串，可为 null
     * @return 非空白原值；为 null 或空白时返回 null
     */
    public static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    /**
     * 将空内容转换为 null。
     *
     * <p>与 {@link #blankToNull(String)} 的区别：本方法只看长度，仅含空白的内容会原样返回，
     * 适用于区分「未出现」与「出现但为空」的场景，例如流式累积缓冲的读取。
     *
     * @param value 待处理的内容，可为 null
     * @return 长度大于 0 时返回其字符串形式；为 null 或长度为 0 时返回 null
     */
    public static String emptyToNull(CharSequence value) {
        return value == null || value.length() == 0 ? null : value.toString();
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
     * <p>严格指遇到非法或不完整字节序列时直接报错，而不是替换，便于调用方按协议错误处理。
     *
     * @param bytes 待解码的字节数组，不可为 null
     * @return 解码后的字符串
     * @throws CharacterCodingException 存在非法或不完整字节序列时
     */
    public static String decodeUtf8Strict(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * 计算字符串在等宽终端中的显示宽度：东亚全角字符占两列，其余字符占一列。
     *
     * <p>为什么按显示宽度而不是字符数：中文与英文混排时，按字符数 补齐会让后续列错位。
     *
     * @param value 待计算的字符串，不可为 null
     * @return 显示宽度，单位为列
     */
    public static int displayWidth(String value) {
        int width = 0;
        for (int index = 0; index < value.length(); index++) {
            width += isFullWidth(value.charAt(index)) ? 2 : 1;
        }
        return width;
    }

    /**
     * 在右侧 补充空格，使文本达到指定显示宽度。
     *
     * @param value 待补充的字符串，不可为 null
     * @param width 目标显示宽度，单位为列
     * @return 显示宽度不小于目标宽度的文本；已足够宽时原样返回
     */
    public static String padRight(String value, int width) {
        int padding = width - displayWidth(value);
        if (padding <= 0) {
            return value;
        }
        StringBuilder padded = new StringBuilder(value);
        for (int index = 0; index < padding; index++) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * 判断字符是否按两列显示：覆盖常用东亚全角区间（汉字、全角标点、假名、谚文）。
     *
     * @param value 待判断字符
     * @return 按两列显示时返回 true
     */
    private static boolean isFullWidth(char value) {
        return value >= 0x1100 && value <= 0x115F
                || value >= 0x2E80 && value <= 0xA4CF
                || value >= 0xAC00 && value <= 0xD7A3
                || value >= 0xF900 && value <= 0xFAFF
                || value >= 0xFE30 && value <= 0xFE6F
                || value >= 0xFF00 && value <= 0xFF60
                || value >= 0xFFE0 && value <= 0xFFE6;
    }
}
