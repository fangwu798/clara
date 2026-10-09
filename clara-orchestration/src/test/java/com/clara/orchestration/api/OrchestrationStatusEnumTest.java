package com.clara.orchestration.api;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * {@link OrchestrationStatusEnum} 单元测试：三态取值与顺序。
 */
public class OrchestrationStatusEnumTest {

    @Test
    public void exposesThreeTerminalStates() {
        OrchestrationStatusEnum[] values = OrchestrationStatusEnum.values();
        assertEquals(3, values.length);
        assertSame(OrchestrationStatusEnum.COMPLETED, values[0]);
        assertSame(OrchestrationStatusEnum.CANCELLED, values[1]);
        assertSame(OrchestrationStatusEnum.FAILED, values[2]);
    }

    @Test
    public void valueOfRoundTripsByConstantName() {
        for (OrchestrationStatusEnum status : OrchestrationStatusEnum.values()) {
            assertSame(status, OrchestrationStatusEnum.valueOf(status.name()));
        }
    }
}
