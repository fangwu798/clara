package com.clara.enums.interaction;

/**
 * ESC 取消确认状态机的决策结果。
 */
public enum CancelConfirmationDecisionEnum {

    /** 与本状态机无关，继续执行。 */
    IGNORED,
    /** 已进入待确认状态，应提示用户「再按一次确认」。 */
    PROMPT,
    /** 用户已确认取消。 */
    CANCEL
}
