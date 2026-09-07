package com.clara.planner;

/**
 * 跟踪预算的预留与使用情况。
 */
public interface BudgetLedger {
    void openRun(String runId, BudgetAmount limit);
    BudgetReservation reserve(String runId, String taskId, BudgetAmount maximum);
    BudgetReservation settle(String reservationId, BudgetAmount actual);
    BudgetReservation release(String reservationId);
    BudgetReservation markUnknown(String reservationId);
    BudgetSnapshot tenantSnapshot();
    BudgetSnapshot runSnapshot(String runId);
}
