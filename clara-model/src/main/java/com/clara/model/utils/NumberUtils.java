package com.clara.model.utils;

/**
 * 提供数字解析与校验的公共工具。
 */
public final class NumberUtils {

    /**
     * 将仅由十进制数字组成的字符串解析为 Long。
     *
     * <p>宽松解析：不做异常抛出，非法输入统一返回 null，适合处理来自外部协议的数值字段
     * （例如 SSE 的 retry 字段），让调用方自行决定是忽略还是报错。
     *
     * @param value 待解析的字符串
     * @return 纯数字且未超出 long 范围时返回对应值；为 null、空串、含非数字字符或超出范围时返回 null
     */
    public static Long parseDigitsOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') {
                return null;
            }
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
