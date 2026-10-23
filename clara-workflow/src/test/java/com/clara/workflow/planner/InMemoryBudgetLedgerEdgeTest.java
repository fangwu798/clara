package com.clara.workflow.planner;

import com.clara.dto.planner.BudgetAmount;
import com.clara.dto.planner.BudgetReservation;
import com.clara.enums.planner.BudgetReservationStatusEnum;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * State-transition boundaries of the budget ledger: duplicate settlement, conflicting reservation,
 * invalid arguments, and settlement beyond the limit must all fail explicitly.
 */
public class InMemoryBudgetLedgerEdgeTest {

    private static final BudgetAmount LIMIT = new BudgetAmount(100L, 100L, 100L);

    @Test
    public void validatesOpenRunArguments() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
        assertThrows(IllegalArgumentException.class, () -> ledger.openRun(null, LIMIT));
        assertThrows(IllegalArgumentException.class, () -> ledger.openRun("  ", LIMIT));
        assertThrows(IllegalArgumentException.class, () -> ledger.openRun("r", null));
    }

    @Test
    public void validatesReserveArguments() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
        ledger.openRun("r", LIMIT);
        assertThrows(IllegalArgumentException.class, () -> ledger.reserve(null, "t", BudgetAmount.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ledger.reserve("r", " ", BudgetAmount.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ledger.reserve("r", "t", null));
    }

    @Test
    public void rejectsConflictingReservationForSameTask() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
        ledger.openRun("r", LIMIT);
        ledger.reserve("r", "t", new BudgetAmount(10L, 10L, 10L));

        ClaraException error = assertThrows(ClaraException.class,
                () -> ledger.reserve("r", "t", new BudgetAmount(20L, 20L, 20L)));
        assertEquals(ErrorCodes.BUDGET_RESERVATION_INVALID, error.getError().getCode());
    }

    @Test
    public void repeatedSettlementIsIdempotentOnlyForSameAmount() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
        ledger.openRun("r", LIMIT);
        BudgetReservation reservation = ledger.reserve("r", "t", new BudgetAmount(20L, 20L, 20L));
        ledger.settle(reservation.getReservationId(), new BudgetAmount(10L, 10L, 10L));

        BudgetReservation repeated = ledger.settle(reservation.getReservationId(),
                new BudgetAmount(10L, 10L, 10L));
        assertEquals(BudgetReservationStatusEnum.SETTLED, repeated.getStatus());
        assertThrows(ClaraException.class,
                () -> ledger.settle(reservation.getReservationId(), new BudgetAmount(11L, 11L, 11L)));
    }

    @Test
    public void settlementOrReleaseAfterSettlementIsRejected() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
        ledger.openRun("r", LIMIT);
        BudgetReservation reservation = ledger.reserve("r", "t", new BudgetAmount(20L, 20L, 20L));
        ledger.settle(reservation.getReservationId(), new BudgetAmount(10L, 10L, 10L));

        assertThrows(ClaraException.class, () -> ledger.release(reservation.getReservationId()));
        assertThrows(ClaraException.class, () -> ledger.markUnknown(reservation.getReservationId()));
    }

    @Test
    public void settlementThatExceedsLimitReportsBudgetExceeded() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
        ledger.openRun("r", LIMIT);
        BudgetReservation reservation = ledger.reserve("r", "t", new BudgetAmount(20L, 20L, 20L));

        ClaraException error = assertThrows(ClaraException.class,
                () -> ledger.settle(reservation.getReservationId(), new BudgetAmount(200L, 200L, 200L)));
        assertEquals(ErrorCodes.BUDGET_EXCEEDED, error.getError().getCode());
    }

    @Test
    public void releaseAndMarkUnknownValidateState() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger();
        ledger.openRun("r", LIMIT);
        BudgetReservation reservation = ledger.reserve("r", "t", new BudgetAmount(20L, 20L, 20L));
        ledger.release(reservation.getReservationId());

        assertThrows(ClaraException.class, () -> ledger.markUnknown(reservation.getReservationId()));
    }
}
