package com.clara.adapter.console;

import com.clara.usage.IUsageLineColorizer;

/**
 * {@code /usage} 统计表与趋势图共用的模型配色：按模型首次出现顺序取色，总量固定为亮白色。
 *
 * <p>两处共用同一套索引规则，保证同一个模型在统计表和趋势图中颜色一致。
 */
public final class UsageColorPalette {

    /** 模型系列可选颜色：按顺序循环使用。 */
    private static final String[] COLORS = {
            ConsoleView.ANSI_CYAN, ConsoleView.ANSI_MAGENTA, ConsoleView.ANSI_YELLOW,
            ConsoleView.ANSI_GREEN, ConsoleView.ANSI_LIGHT_BLUE, ConsoleView.ANSI_RED
    };

    private UsageColorPalette() {
    }

    /**
     * 按系列序号取颜色；超出调色板长度时循环。
     *
     * @param index 系列序号，可为负数
     * @return ANSI 颜色码，非 null
     */
    public static String colorFor(int index) {
        return COLORS[Math.floorMod(index, COLORS.length)];
    }

    /**
     * 给文本着色；颜色关闭时原样返回。
     *
     * @param color    ANSI 颜色码，可为 null
     * @param text     待着色文本，可为 null
     * @param enabled  是否启用颜色
     * @return 着色后的文本；未启用或入参为空时返回原文
     */
    public static String colorize(String color, String text, boolean enabled) {
        if (!enabled || color == null || text == null) {
            return text;
        }
        return color + text + ConsoleView.ANSI_RESET;
    }

    /**
     * 按系列标识给文本着色：总量用亮白色，其余按序号取模型颜色。
     *
     * @param key     系列标识，可为 null
     * @param index   模型系列序号
     * @param text    待着色文本，可为 null
     * @param enabled 是否启用颜色
     * @return 着色后的文本；未启用或入参为空时返回原文
     */
    public static String colorize(String key, int index, String text, boolean enabled) {
        if (IUsageLineColorizer.TOTAL_KEY.equals(key)) {
            return colorize(ConsoleView.ANSI_WHITE, text, enabled);
        }
        return colorize(colorFor(index), text, enabled);
    }
}
