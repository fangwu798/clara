package com.clara.planner;

public interface BudgetLedger {
    void openRun(String runId, BudgetAmount limit);
    BudgetReservation reserve(String runId, String taskId, BudgetAmount maximum);
    BudgetReservation settle(String reservationId, BudgetAmount actual);
    BudgetReservation release(String reservationId);
    BudgetReservation markUnknown(String reservationId);
    BudgetSnapshot tenantSnapshot();
    BudgetSnapshot runSnapshot(String runId);
}
