package com.clara.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * token 数量的展示格式：按万、千万、亿换算，保留两位小数。
 *
 * <p>小于 1 万时直接展示原值，不附加单位；其余区间依次使用「万」「千万」「亿」，
 * 避免在 {@code /usage} 中直接展示难以阅读的长数字。
 */
public final class TokenCountFormatter {

    /** 一万。 */
    private static final long TEN_THOUSAND = 10_000L;

    /** 一千万。 */
    private static final long TEN_MILLION = 10_000_000L;

    /** 一亿。 */
    private static final long HUNDRED_MILLION = 100_000_000L;

    private TokenCountFormatter() {
    }

    /**
     * 格式化 token 数量。
     *
     * @param tokens token 数量
     * @return 展示文本，非 null
     */
    public static String format(long tokens) {
        if (tokens < TEN_THOUSAND) {
            return Long.toString(tokens);
        }
        if (tokens <= TEN_MILLION) {
            return scaled(tokens, TEN_THOUSAND, "万");
        }
        if (tokens < HUNDRED_MILLION) {
            return scaled(tokens, TEN_MILLION, "千万");
        }
        return scaled(tokens, HUNDRED_MILLION, "亿");
    }

    /**
     * 按指定单位换算并保留两位小数。
     *
     * @param tokens   token 数量
     * @param unit     单位对应的数值
     * @param unitName 单位名称
     * @return 换算后的展示文本，非 null
     */
    private static String scaled(long tokens, long unit, String unitName) {
        BigDecimal value = BigDecimal.valueOf(tokens)
                .divide(BigDecimal.valueOf(unit), 2, RoundingMode.HALF_UP);
        return value.toPlainString() + unitName;
    }
}
