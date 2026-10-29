package com.clara.utils;

/**
 * 耗时展示的公共格式化工具：不足一分钟保持原单位，达到一分钟按「分 / 小时 / 天」逐级进位。
 *
 * <p>定位：无状态的纯函数集合，不依赖任何业务类型，供各模块的控制台展示复用。
 * <p>使用方法：
 * <ul>
 *   <li>已有秒数（如 Working 计时）用 {@link #formatSeconds(long)}：{@code 12} 输出 {@code 12s}；</li>
 *   <li>已有毫秒耗时（如轮次汇总、检索耗时）用 {@link #formatMillis(long)}：{@code 576} 输出 {@code 576ms}；</li>
 *   <li>两者达到 60 秒后统一输出分秒，如 {@code 1分5秒}；达到 1 小时进位为 {@code 1小时1分1秒}；
 *       达到 1 天进位为 {@code 1天1小时1分1秒}，不再展示 ms / s 单位。</li>
 * </ul>
 *
 * <p>线程安全：无状态、无可变字段，可跨线程并发调用。
 * <p>失败语义：不抛异常；负数按 0 处理，避免耗时计算误差在控制台输出负值。
 */
public final class DurationUtils {

    /** 一分钟对应的毫秒数。 */
    private static final long MILLIS_PER_MINUTE = 60_000L;

    /** 一分钟对应的秒数。 */
    private static final long SECONDS_PER_MINUTE = 60L;

    /** 一小时对应的分钟数。 */
    private static final long MINUTES_PER_HOUR = 60L;

    /** 一天对应的小时数。 */
    private static final long HOURS_PER_DAY = 24L;

    private DurationUtils() {
    }

    /**
     * 格式化毫秒耗时。
     *
     * @param millis 耗时毫秒，负数按 0 处理
     * @return 不足一分钟为 {@code 1234ms}；60 秒起为 {@code 1分5秒}；
     *         1 小时起为 {@code 1小时1分1秒}；1 天起为 {@code 1天1小时1分1秒}，非 null
     */
    public static String formatMillis(long millis) {
        long safeMillis = Math.max(0L, millis);
        if (safeMillis < MILLIS_PER_MINUTE) {
            return safeMillis + "ms";
        }
        return formatSeconds(safeMillis / 1000L);
    }

    /**
     * 格式化秒级计时。
     *
     * @param seconds 已用秒数，负数按 0 处理
     * @return 不足一分钟为 {@code 12s}；60 秒起为 {@code 1分5秒}；
     *         1 小时起为 {@code 1小时1分1秒}；1 天起为 {@code 1天1小时1分1秒}，非 null
     */
    public static String formatSeconds(long seconds) {
        long safeSeconds = Math.max(0L, seconds);
        if (safeSeconds < SECONDS_PER_MINUTE) {
            return safeSeconds + "s";
        }
        return clockText(safeSeconds);
    }

    /**
     * 把总秒数拼成「分秒 / 小时分秒 / 天小时分秒」。
     *
     * <p>最高单位以下的所有单位都保留（含 0），保证同一量级的输出宽度稳定：
     * 3600 秒输出 {@code 1小时0分0秒}，而不是 {@code 1小时}。
     *
     * @param totalSeconds 总秒数，非负
     * @return 形如 {@code 1天1小时1分1秒} 的文本，非 null
     */
    private static String clockText(long totalSeconds) {
        long totalMinutes = totalSeconds / SECONDS_PER_MINUTE;
        long totalHours = totalMinutes / MINUTES_PER_HOUR;
        long days = totalHours / HOURS_PER_DAY;
        long hours = totalHours % HOURS_PER_DAY;
        long minutes = totalMinutes % MINUTES_PER_HOUR;
        long seconds = totalSeconds % SECONDS_PER_MINUTE;
        StringBuilder text = new StringBuilder();
        if (days > 0L) {
            text.append(days).append("天");
        }
        if (totalHours > 0L) {
            text.append(hours).append("小时");
        }
        return text.append(minutes).append("分").append(seconds).append("秒").toString();
    }
}
