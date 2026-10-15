package com.clara.adapter.console;

import com.clara.model.availability.AvailabilityLine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 可用性检查行的着色规则：可用为绿色、跳过为黄色、不可用为红色、普通信息不染色。
 */
public class ConsoleViewAvailabilityColorTest {

    /**
     * 状态级别与 ANSI 颜色码一一对应。
     */
    @Test
    public void mapsAvailabilityLevelToAnsiColor() {
        assertEquals(ConsoleView.ANSI_GREEN, ConsoleView.colorOf(AvailabilityLine.Level.OK));
        assertEquals(ConsoleView.ANSI_YELLOW, ConsoleView.colorOf(AvailabilityLine.Level.WARN));
        assertEquals(ConsoleView.ANSI_RED, ConsoleView.colorOf(AvailabilityLine.Level.FAIL));
        assertNull(ConsoleView.colorOf(AvailabilityLine.Level.INFO));
        assertNull(ConsoleView.colorOf(null));
    }
}
