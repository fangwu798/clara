package com.clara.enums.interaction;

/**
 * 会话内指令的处理结果。
 */
public enum SessionCommandOutcomeEnum {

    /** 结束会话。 */
    EXIT,
    /** 指令已处理，跳过本轮对话。 */
    HANDLED,
    /** 非指令，按对话输入处理。 */
    INPUT
}
