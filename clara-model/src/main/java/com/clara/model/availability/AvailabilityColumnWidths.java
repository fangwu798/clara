package com.clara.model.availability;

import lombok.Getter;

/**
 * 可用性检查输出行的前三列宽度：状态、别名、平台与模型。
 *
 * <p>三列宽度只取决于「会出现哪些别名」「会出现哪些平台与模型」，可在发起请求前算好，
 * 从而支持「检查完一个就输出一个」时仍保持整批对齐；结论列紧随其后，不需要单独定宽。
 */
@Getter
public final class AvailabilityColumnWidths {

    /**
     * 状态列宽度，单位为显示列。
     * -- GETTER --
     * 状态列宽度。
     *
     * @return 显示宽度，单位为列
     */
    private final int statusWidth;

    /**
     * 别名列宽度，单位为显示列。
     * -- GETTER --
     * 别名列宽度。
     *
     * @return 显示宽度，单位为列
     */
    private final int aliasWidth;

    /**
     * 「平台与模型」列宽度，单位为显示列。
     * -- GETTER --
     * 「平台与模型」列宽度。
     *
     * @return 显示宽度，单位为列
     */
    private final int identityWidth;

    /**
     * 创建列宽。
     *
     * @param statusWidth   状态列宽度，不得为负
     * @param aliasWidth    别名列宽度，不得为负
     * @param identityWidth 「平台与模型」列宽度，不得为负
     * @throws IllegalArgumentException 宽度为负时
     */
    public AvailabilityColumnWidths(int statusWidth, int aliasWidth, int identityWidth) {
        if (statusWidth < 0 || aliasWidth < 0 || identityWidth < 0) {
            throw new IllegalArgumentException("column widths must not be negative");
        }
        this.statusWidth = statusWidth;
        this.aliasWidth = aliasWidth;
        this.identityWidth = identityWidth;
    }

}
