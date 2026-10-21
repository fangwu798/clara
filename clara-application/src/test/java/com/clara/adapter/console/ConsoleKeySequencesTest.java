package com.clara.adapter.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Windows 按键映射：带修饰键的回车需要改写成行编辑器可识别的序列。
 */
public class ConsoleKeySequencesTest {

    /**
     * 回车键码为 13，Ctrl 修饰位为 4。
     */
    @Test
    public void mapsCtrlEnter() {
        assertEquals(ConsoleKeySequences.CTRL_ENTER,
                ConsoleKeySequences.windowsEscapeSequence((short) 13, 4));
    }

    /**
     * Ctrl+Shift+Enter 同样应识别为 Ctrl+Enter。
     */
    @Test
    public void mapsCtrlShiftEnter() {
        assertEquals(ConsoleKeySequences.CTRL_ENTER,
                ConsoleKeySequences.windowsEscapeSequence((short) 13, 5));
    }

    /**
     * Shift+Enter 应识别为换行序列，而不是提交。
     */
    @Test
    public void mapsShiftEnter() {
        assertEquals(ConsoleKeySequences.SHIFT_ENTER,
                ConsoleKeySequences.windowsEscapeSequence((short) 13, 1));
    }

    /**
     * 单独 Enter 保持 JLine 默认行为，不做改写。
     */
    @Test
    public void keepsPlainEnter() {
        assertNull(ConsoleKeySequences.windowsEscapeSequence((short) 13, 0));
    }

    /**
     * 非回车键不受影响。
     */
    @Test
    public void ignoresOtherKeys() {
        assertNull(ConsoleKeySequences.windowsEscapeSequence((short) 65, 4));
    }
}
