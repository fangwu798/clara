package com.clara.enums.interaction;

/**
 * 历史记录清理范围：区分「全部清空」与「从两端按条数清理」。
 *
 * <p>{@code /history clear} 使用 {@link #ALL}；{@code /history clear -n xx} 使用 {@link #FIRST}
 * （最早的 xx 条）；{@code /history clear -t xx} 使用 {@link #LAST}（最新的 xx 条）。
 */
public enum HistoryClearScopeEnum {

    /** 清空全部历史记录。 */
    ALL,

    /** 从最早的消息开始清理。 */
    FIRST,

    /** 从最新的消息开始清理。 */
    LAST
}
