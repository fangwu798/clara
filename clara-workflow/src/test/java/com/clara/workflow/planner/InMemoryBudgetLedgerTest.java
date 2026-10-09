package com.clara.workflow.planner;

import com.clara.dto.planner.BudgetAmount;
import com.clara.dto.planner.BudgetReservation;
import com.clara.dto.planner.BudgetSnapshot;
import com.clara.enums.planner.BudgetReservationStatusEnum;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * {@link InMemoryBudgetLedger} 的单元测试：预留 / 结算 / 释放 / 未知态、超限拒绝与快照。
 */
public class InMemoryBudgetLedgerTest {

    private static final BudgetAmount LIMIT = new BudgetAmount(100L, 100L, 100L);

    @Test
    public void reservesAndSettles() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
        ledger.openRun("r", LIMIT);

        BudgetReservation reservation = ledger.reserve("r", "t1", new BudgetAmount(50L, 50L, 50L));
        assertEquals(BudgetReservationStatusEnum.RESERVED, reservation.getStatus());
        assertEquals("r:t1", reservation.getReservationId());

        BudgetSnapshot reserved = ledger.runSnapshot("r");
        assertEquals(new BudgetAmount(50L, 50L, 50L), reserved.getReserved());
        assertEquals(BudgetAmount.ZERO, reserved.getUsed());

        BudgetReservation settled = ledger.settle(reservation.getReservationId(),
                new BudgetAmount(10L, 10L, 10L));
        assertEquals(BudgetReservationStatusEnum.SETTLED, settled.getStatus());
        assertEquals(new BudgetAmount(10L, 10L, 10L), settled.getActual());

        BudgetSnapshot afterSettle = ledger.runSnapshot("r");
        assertEquals(BudgetAmount.ZERO, afterSettle.getReserved());
        assertEquals(new BudgetAmount(10L, 10L, 10L), afterSettle.getUsed());
    }

    @Test
    public void releasesReservation() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
        ledger.openRun("r", LIMIT);
        BudgetReservation reservation = ledger.reserve("r", "t1", new BudgetAmount(50L, 50L, 50L));

        BudgetReservation released = ledger.release(reservation.getReservationId());

        assertEquals(BudgetReservationStatusEnum.RELEASED, released.getStatus());
        assertEquals(BudgetAmount.ZERO, ledger.runSnapshot("r").getReserved());
        assertEquals(BudgetReservationStatusEnum.RELEASED,
                ledger.release(reservation.getReservationId()).getStatus());
    }

    @Test
    public void marksReservationUnknown() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
        ledger.openRun("r", LIMIT);
        BudgetReservation reservation = ledger.reserve("r", "t1", new BudgetAmount(50L, 50L, 50L));

        BudgetReservation unknown = ledger.markUnknown(reservation.getReservationId());

        assertEquals(BudgetReservationStatusEnum.UNKNOWN, unknown.getStatus());
        assertEquals(BudgetReservationStatusEnum.UNKNOWN,
                ledger.markUnknown(reservation.getReservationId()).getStatus());
    }

    @Test
    public void rejectsReservationBeyondLimit() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
        ledger.openRun("r", LIMIT);
        ledger.reserve("r", "t1", new BudgetAmount(60L, 60L, 60L));

        ClaraException error = assertThrows(ClaraException.class,
                () -> ledger.reserve("r", "t2", new BudgetAmount(60L, 60L, 60L)));
        assertEquals(ErrorCodes.BUDGET_EXCEEDED, error.getError().getCode());
    }

    @Test
    public void reserveIsIdempotent() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
        ledger.openRun("r", LIMIT);

        BudgetReservation first = ledger.reserve("r", "t1", new BudgetAmount(30L, 30L, 30L));
        BudgetReservation second = ledger.reserve("r", "t1", new BudgetAmount(30L, 30L, 30L));

        assertEquals(first.getReservationId(), second.getReservationId());
        assertEquals(new BudgetAmount(30L, 30L, 30L), ledger.runSnapshot("r").getReserved());
    }

    @Test
    public void rejectsConflictingLimitForSameRun() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
        ledger.openRun("r", LIMIT);
        ledger.openRun("r", LIMIT);

        ClaraException error = assertThrows(ClaraException.class,
                () -> ledger.openRun("r", new BudgetAmount(1L, 1L, 1L)));
        assertEquals(ErrorCodes.BUDGET_RESERVATION_INVALID, error.getError().getCode());
    }

    @Test
    public void rejectsOperationsOnUnknownState() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();

        ClaraException reserve = assertThrows(ClaraException.class,
                () -> ledger.reserve("missing", "t", BudgetAmount.ZERO));
        assertEquals(ErrorCodes.BUDGET_RESERVATION_INVALID, reserve.getError().getCode());

        ClaraException snapshot = assertThrows(ClaraException.class, () -> ledger.runSnapshot("missing"));
        assertEquals(ErrorCodes.BUDGET_RESERVATION_INVALID, snapshot.getError().getCode());

        ClaraException settle = assertThrows(ClaraException.class,
                () -> ledger.settle("missing", BudgetAmount.ZERO));
        assertEquals(ErrorCodes.BUDGET_RESERVATION_INVALID, settle.getError().getCode());
    }
}
