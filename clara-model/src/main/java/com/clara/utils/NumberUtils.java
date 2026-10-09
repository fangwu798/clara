package com.clara.utils;

/**
 * 数字解析的公共工具。
 *
 * <p>定位：无状态的纯函数集合；面向「外部协议传入的数字文本」提供宽松解析，
 * 不在这里做范围校验与业务语义判断。
 *
 * <p>使用方法：
 * <ul>
 *   <li>无需实例化——构造器私有，全部方法为 {@code static}；</li>
 *   <li>解析 SSE 的 {@code retry} 等可选数值字段时调用 {@link #parseDigitsOrNull(String)}，
 *       返回 null 即表示「不是合法数字」，由调用方决定忽略还是报错。</li>
 * </ul>
 *
 * <p>线程安全：无状态、无可变字段，可跨线程并发调用。
 *
 * <p>失败语义：不抛异常——非法输入统一以 null 表达，因此调用方必须显式判空。
 */
public final class NumberUtils {

    private NumberUtils() {
    }

    /**
     * 将仅由十进制数字组成的字符串解析为 Long。
     *
     * <p>宽松解析：不做异常抛出，非法输入统一返回 null，适合处理来自外部协议的数值字段
     * （例如 SSE 的 retry 字段），让调用方自行决定是忽略还是报错。
     *
     * @param value 待解析的字符串，可为 null
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
