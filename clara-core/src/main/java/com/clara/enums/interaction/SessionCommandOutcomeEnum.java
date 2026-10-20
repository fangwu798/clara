package com.clara.enums.interaction;

/**
 * 会话内指令的处理结果。
 */
public enum SessionCommandOutcomeEnum {

    /** 结束会话。 */
    EXIT,
    /** 重新初始化：重新检测模型可用性并重建装配，随后继续当前会话。 */
    REINIT,
    /** 指令已处理，跳过本轮对话。 */
    HANDLED,
    /** 非指令，按对话输入处理。 */
    INPUT
}
