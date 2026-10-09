package com.clara.core.planner;

import com.clara.dto.planner.BudgetAmount;
import com.clara.dto.planner.BudgetReservation;
import com.clara.dto.planner.BudgetSnapshot;

/**
 * 预算账本接口：跟踪预算的预留与使用情况。
 *
 * <p>无租户：预算以运行（run）为维度管理，不提供跨运行的租户级快照。
 */
public interface IBudgetLedger {

    /**
     * 为一个运行打开预算账户。
     */
    void openRun(String runId, BudgetAmount limit);

    /**
     * 为任务预留预算。
     */
    BudgetReservation reserve(String runId, String taskId, BudgetAmount maximum);

    /**
     * 结算预留。
     */
    BudgetReservation settle(String reservationId, BudgetAmount actual);

    /**
     * 释放预留。
     */
    BudgetReservation release(String reservationId);

    /**
     * 标记预留状态未知。
     */
    BudgetReservation markUnknown(String reservationId);

    /**
     * 读取一个运行的预算快照。
     */
    BudgetSnapshot runSnapshot(String runId);
}
