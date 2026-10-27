package com.clara.utils;

/**
 * 耗时展示的公共格式化工具：不足一分钟保持原单位，达到一分钟统一显示为「x分y秒」。
 *
 * <p>定位：无状态的纯函数集合，不依赖任何业务类型，供各模块的控制台展示复用。
 *
 * <p>使用方法：
 * <ul>
 *   <li>已有秒数（如 Working 计时）用 {@link #formatSeconds(long)}：{@code 12} 输出 {@code 12s}；</li>
 *   <li>已有毫秒耗时（如轮次汇总、检索耗时）用 {@link #formatMillis(long)}：{@code 576} 输出 {@code 576ms}；</li>
 *   <li>两者达到 60 秒后均输出 {@code 1分5秒} 形式，不再展示 ms / s 单位。</li>
 * </ul>
 *
 * <p>线程安全：无状态、无可变字段，可跨线程并发调用。
 *
 * <p>失败语义：不抛异常；负数按 0 处理，避免异常耗时代码在控制台输出负值。
 */
public final class DurationUtils {

    /** 一分钟对应的毫秒数。 */
    private static final long MILLIS_PER_MINUTE = 60_000L;

    /** 一分钟对应的秒数。 */
    private static final long SECONDS_PER_MINUTE = 60L;

    private DurationUtils() {
    }

    /**
     * 格式化毫秒耗时。
     *
     * @param millis 耗时毫秒，负数按 0 处理
     * @return 不足一分钟为 {@code 1234ms}，达到一分钟为 {@code 1分5秒}，非 null
     */
    public static String formatMillis(long millis) {
        long safeMillis = Math.max(0L, millis);
        if (safeMillis < MILLIS_PER_MINUTE) {
            return safeMillis + "ms";
        }
        return minutesSeconds(safeMillis / 1000L);
    }

    /**
     * 格式化秒级计时。
     *
     * @param seconds 已用秒数，负数按 0 处理
     * @return 不足一分钟为 {@code 12s}，达到一分钟为 {@code 1分5秒}，非 null
     */
    public static String formatSeconds(long seconds) {
        long safeSeconds = Math.max(0L, seconds);
        if (safeSeconds < SECONDS_PER_MINUTE) {
            return safeSeconds + "s";
        }
        return minutesSeconds(safeSeconds);
    }

    /**
     * 把总秒数拼成「x分y秒」。
     *
     * @param totalSeconds 总秒数，非负
     * @return 形如 {@code 1分5秒} 的文本，非 null
     */
    private static String minutesSeconds(long totalSeconds) {
        return (totalSeconds / SECONDS_PER_MINUTE) + "分" + (totalSeconds % SECONDS_PER_MINUTE) + "秒";
    }
}
