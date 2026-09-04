package com.clara.planner;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class BudgetLedgerTest {
    @Test
    public void reservesSettlesReleasesAndRetainsUnknownUsage() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger(new BudgetAmount(100, 100, 100));
        ledger.openRun("run", new BudgetAmount(50, 50, 50));
        BudgetReservation one = ledger.reserve("run", "one", new BudgetAmount(20, 0, 0));
        ledger.settle(one.getReservationId(), new BudgetAmount(5, 0, 0));
        BudgetReservation two = ledger.reserve("run", "two", new BudgetAmount(10, 0, 0));
        ledger.markUnknown(two.getReservationId());

        assertEquals(new BudgetAmount(5, 0, 0), ledger.runSnapshot("run").getUsed());
        assertEquals(new BudgetAmount(10, 0, 0), ledger.runSnapshot("run").getReserved());
    }

    @Test
    public void concurrentReservationsCannotOversellLimit() throws Exception {
        final InMemoryBudgetLedger ledger = new InMemoryBudgetLedger(new BudgetAmount(10, 0, 0));
        ledger.openRun("run", new BudgetAmount(10, 0, 0));
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger succeeded = new AtomicInteger();
        final AtomicInteger exceeded = new AtomicInteger();
        Thread one = reserver(ledger, "one", start, succeeded, exceeded);
        Thread two = reserver(ledger, "two", start, succeeded, exceeded);
        one.start(); two.start(); start.countDown(); one.join(); two.join();
        assertEquals(1, succeeded.get()); assertEquals(1, exceeded.get());
        assertEquals(new BudgetAmount(7, 0, 0), ledger.runSnapshot("run").getReserved());
    }

    @Test
    public void recordsActualUsageEvenWhenItExceedsReservation() {
        InMemoryBudgetLedger ledger = new InMemoryBudgetLedger(new BudgetAmount(10, 0, 0));
        ledger.openRun("run", new BudgetAmount(10, 0, 0));
        BudgetReservation reservation = ledger.reserve("run", "task", new BudgetAmount(5, 0, 0));
        try {
            ledger.settle(reservation.getReservationId(), new BudgetAmount(12, 0, 0));
            fail("expected budget exceeded");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.BUDGET_EXCEEDED, exception.getError().getCode());
        }
        assertEquals(new BudgetAmount(12, 0, 0), ledger.runSnapshot("run").getUsed());
        assertEquals(BudgetReservationStatus.SETTLED,
                ledger.settle(reservation.getReservationId(), new BudgetAmount(12, 0, 0)).getStatus());
    }

    private static Thread reserver(final InMemoryBudgetLedger ledger, final String task,
            final CountDownLatch start, final AtomicInteger succeeded, final AtomicInteger exceeded) {
        return new Thread(new Runnable() {
            public void run() {
                try { start.await(); ledger.reserve("run", task, new BudgetAmount(7, 0, 0)); succeeded.incrementAndGet(); }
                catch (ClaraException exception) {
                    if (ErrorCodes.BUDGET_EXCEEDED.equals(exception.getError().getCode())) exceeded.incrementAndGet();
                } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
        });
    }
}
