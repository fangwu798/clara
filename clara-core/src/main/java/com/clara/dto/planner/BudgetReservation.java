package com.clara.dto.planner;

import com.clara.enums.planner.BudgetReservationStatusEnum;

/**
 * 预算预留：记录一次任务执行的预算预留、实际使用与状态。
 */
public final class BudgetReservation {

    private final String reservationId;
    private final String runId;
    private final String taskId;
    private final BudgetAmount maximum;
    private final BudgetAmount actual;
    private final BudgetReservationStatusEnum status;

    /**
     * 创建预算预留。
     */
    public BudgetReservation(String reservationId, String runId, String taskId, BudgetAmount maximum,
            BudgetAmount actual, BudgetReservationStatusEnum status) {
        this.reservationId = reservationId;
        this.runId = runId;
        this.taskId = taskId;
        this.maximum = maximum;
        this.actual = actual;
        this.status = status;
    }

    public String getReservationId() {
        return reservationId;
    }

    public String getRunId() {
        return runId;
    }

    public String getTaskId() {
        return taskId;
    }

    public BudgetAmount getMaximum() {
        return maximum;
    }

    public BudgetAmount getActual() {
        return actual;
    }

    public BudgetReservationStatusEnum getStatus() {
        return status;
    }
}
