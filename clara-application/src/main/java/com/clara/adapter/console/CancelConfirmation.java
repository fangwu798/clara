package com.clara.adapter.console;

import com.clara.enums.interaction.CancelConfirmationDecisionEnum;

/**
 * ESC 取消的确认状态机：把控制台的单键输入翻译为「提示确认 / 确认取消 / 忽略」三种决策。
 *
 * <p>是什么、在哪一层：application 边界适配层（{@code com.clara.adapter.console}）的小型状态机，不依赖终端与
 * 线程，便于单测；由 {@link CancelKeyMonitor} 的按键循环驱动。
 *
 * <p>为什么需要确认：执行过程防误触——第一次 ESC 只提示「再按一次确认」，第二次 ESC（或回车 / {@code y}）才
 * 真正取消；确认窗口内按其他键，或超时未再按，都放弃取消并回到继续执行。
 */
final class CancelConfirmation {

    /** ESC 键值。 */
    static final int ESC = 27;
    /** 回车键值。 */
    static final int CARRIAGE_RETURN = 13;
    /** 换行键值。 */
    static final int LINE_FEED = 10;

    /**
     * 确认窗口时长（毫秒）：按下 ESC 后需在此期间再次确认，否则放弃取消。
     */
    private final long confirmWindowMillis;

    /**
     * 是否正在等待第二次按键确认。
     */
    private boolean awaiting;
    /**
     * 确认窗口的截止时刻（毫秒）；仅在 {@link #awaiting} 为真时有意义。
     */
    private long deadline;

    /**
     * 创建状态机。
     *
     * @param confirmWindowMillis 确认窗口时长（毫秒），必须为正
     */
    CancelConfirmation(long confirmWindowMillis) {
        if (confirmWindowMillis <= 0L) {
            throw new IllegalArgumentException("confirmWindowMillis must be positive");
        }
        this.confirmWindowMillis = confirmWindowMillis;
    }

    /**
     * 是否处于待确认状态（等待第二次按键）。
     *
     * @return 待确认时返回 {@code true}
     */
    boolean isAwaiting() {
        return awaiting;
    }

    /**
     * 处理一个按键。
     *
     * @param key 键值
     * @param now 当前时间（Unix epoch 毫秒）
     * @return 决策：首次 ESC 返回 {@link CancelConfirmationDecisionEnum#PROMPT}，
     *         确认键返回 {@link CancelConfirmationDecisionEnum#CANCEL}，其余返回
     *         {@link CancelConfirmationDecisionEnum#IGNORED}
     */
    CancelConfirmationDecisionEnum onKey(int key, long now) {
        if (!awaiting) {
            if (key != ESC) {
                return CancelConfirmationDecisionEnum.IGNORED;
            }
            awaiting = true;
            deadline = now + confirmWindowMillis;
            return CancelConfirmationDecisionEnum.PROMPT;
        }
        awaiting = false;
        if (key == ESC || key == CARRIAGE_RETURN || key == LINE_FEED || key == 'y' || key == 'Y') {
            return CancelConfirmationDecisionEnum.CANCEL;
        }
        return CancelConfirmationDecisionEnum.IGNORED;
    }

    /**
     * 处理确认窗口超时：仍在窗口内返回 {@code false}，已超时则复位待确认状态并返回 {@code true}。
     *
     * @param now 当前时间（Unix epoch 毫秒）
     * @return 本次是否因超时而放弃取消
     */
    boolean onTimeout(long now) {
        if (awaiting && now >= deadline) {
            awaiting = false;
            return true;
        }
        return false;
    }
}
