package com.clara.adapter.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link CancelConfirmation} 的单元测试：ESC 需二次确认，其他键或超时放弃取消。
 */
public class CancelConfirmationTest {

    private static final long WINDOW = 5_000L;

    @Test
    public void firstEscPromptsAndSecondEscCancels() {
        CancelConfirmation confirmation = new CancelConfirmation(WINDOW);

        assertEquals(CancelConfirmation.Decision.PROMPT, confirmation.onKey(CancelConfirmation.ESC, 0L));
        assertTrue(confirmation.isAwaiting());
        assertEquals(CancelConfirmation.Decision.CANCEL, confirmation.onKey(CancelConfirmation.ESC, 1_000L));
        assertFalse(confirmation.isAwaiting());
    }

    @Test
    public void nonConfirmationKeyWhileAwaitingIsIgnored() {
        CancelConfirmation confirmation = new CancelConfirmation(WINDOW);
        confirmation.onKey(CancelConfirmation.ESC, 0L);

        assertEquals(CancelConfirmation.Decision.IGNORED, confirmation.onKey('a', 100L));
        assertFalse("忽略后应退出待确认状态", confirmation.isAwaiting());
    }

    @Test
    public void enterConfirmsCancellation() {
        CancelConfirmation confirmation = new CancelConfirmation(WINDOW);
        confirmation.onKey(CancelConfirmation.ESC, 0L);

        assertEquals(CancelConfirmation.Decision.CANCEL,
                confirmation.onKey(CancelConfirmation.CARRIAGE_RETURN, 100L));
    }

    @Test
    public void plainKeyWithoutEscIsIgnored() {
        CancelConfirmation confirmation = new CancelConfirmation(WINDOW);

        assertEquals(CancelConfirmation.Decision.IGNORED, confirmation.onKey('x', 0L));
        assertFalse(confirmation.isAwaiting());
    }

    @Test
    public void timeoutAbandonsCancellation() {
        CancelConfirmation confirmation = new CancelConfirmation(WINDOW);
        confirmation.onKey(CancelConfirmation.ESC, 1_000L);

        assertFalse("窗口内不应超时", confirmation.onTimeout(5_000L));
        assertTrue("超出窗口应放弃取消", confirmation.onTimeout(6_001L));
        assertFalse(confirmation.isAwaiting());
    }
}
