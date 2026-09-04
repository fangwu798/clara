package com.clara.planner;

public final class BudgetReservation {
    private final String reservationId;
    private final String runId;
    private final String taskId;
    private final BudgetAmount maximum;
    private final BudgetAmount actual;
    private final BudgetReservationStatus status;
    BudgetReservation(String reservationId, String runId, String taskId, BudgetAmount maximum,
            BudgetAmount actual, BudgetReservationStatus status) {
        this.reservationId = reservationId; this.runId = runId; this.taskId = taskId;
        this.maximum = maximum; this.actual = actual; this.status = status;
    }
    public String getReservationId() { return reservationId; }
    public String getRunId() { return runId; }
    public String getTaskId() { return taskId; }
    public BudgetAmount getMaximum() { return maximum; }
    public BudgetAmount getActual() { return actual; }
    public BudgetReservationStatus getStatus() { return status; }
}
