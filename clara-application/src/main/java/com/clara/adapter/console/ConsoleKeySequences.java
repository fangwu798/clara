package com.clara.adapter.console;

/**
 * 控制台按键序列：集中定义行编辑器与终端实现共用的转义序列。
 *
 * <p>为什么需要这里：Windows 控制台的 {@code Ctrl+Enter} / {@code Shift+Enter} 与 {@code Enter}
 * 在 JLine 默认实现里都会被翻译成
 * 同一个回车字符 {@code \r}，行编辑器无法区分。{@link org.jline.terminal.impl.jna.win.ClaraWinSysTerminal}
 * 在终端层把带修饰键的回车改写成本类定义的序列，行编辑器再把该序列绑定为「插入换行」。
 */
public final class ConsoleKeySequences {

    /**
     * {@code Ctrl+Enter} 的 CSI-u 序列；与 Kitty 键盘协议保持一致，便于跨终端复用同一绑定。
     */
    public static final String CTRL_ENTER = "\033[13;5u";

    /**
     * {@code Shift+Enter} 的 CSI-u 序列；与 Kitty 键盘协议保持一致。
     */
    public static final String SHIFT_ENTER = "\033[13;2u";

    /**
     * Windows 虚拟键码：回车。
     */
    private static final short VIRTUAL_KEY_RETURN = 13;

    /**
     * JLine 传入的修饰键位：Ctrl。
     */
    private static final int CTRL_MODIFIER = 4;

    /**
     * JLine 传入的修饰键位：Shift。
     */
    private static final int SHIFT_MODIFIER = 1;

    private ConsoleKeySequences() {
    }

    /**
     * 把 Windows 按键事件映射为行编辑器可识别的转义序列。
     *
     * @param virtualKey 虚拟键码
     * @param modifier   JLine 组装的修饰键位（Ctrl=4、Alt=2、Shift=1）
     * @return 需要注入的序列；不需要改写时返回 {@code null}
     */
    public static String windowsEscapeSequence(short virtualKey, int modifier) {
        if (virtualKey == VIRTUAL_KEY_RETURN) {
            if ((modifier & CTRL_MODIFIER) != 0) {
                return CTRL_ENTER;
            }
            if ((modifier & SHIFT_MODIFIER) != 0) {
                return SHIFT_ENTER;
            }
        }
        return null;
    }
}
