package com.clara.usage;

/**
 * {@code /usage} 报告的行着色器：SDK 只产出纯文本，由宿主决定是否以及如何加 ANSI 颜色。
 *
 * <p>使用方法：实现方按系列标识返回着色后的整行；返回 {@code null} 表示保留原始行。
 * 颜色开关由宿主自行判断，SDK 不感知终端能力。
 */
@FunctionalInterface
public interface IUsageLineColorizer {

    /** 总量行的系列标识。 */
    String TOTAL_KEY = "__total__";

    /**
     * 给一行用量文本着色。
     *
     * @param key  系列标识：模型为 {@code PROVIDER/model}，总量为 {@link #TOTAL_KEY}
     * @param line 原始行，非 null
     * @return 着色后的行；返回 null 表示不着色
     */
    String colorize(String key, String line);
}
