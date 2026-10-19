package com.clara.dto.planner;

import lombok.Data;

/**
 * 计划限制：约束任务数、步骤数、重规划次数、并行度、总耗时与总预算。
 */
@Data
public final class PlanLimits {

    private final int maxTasks;
    private final int maxPlanSteps;
    private final int maxRePlans;
    private final int maxParallelism;
    private final long totalTimeoutMillis;
    private final BudgetAmount totalBudget;

    /**
     * 创建计划限制。
     */
    public PlanLimits(int maxTasks, int maxPlanSteps, int maxRePlans, int maxParallelism, long totalTimeoutMillis, BudgetAmount totalBudget) {
        if (maxTasks <= 0 || maxPlanSteps <= 0 || maxRePlans < 0 || maxParallelism <= 0 || totalTimeoutMillis <= 0L || totalBudget == null) {
            throw new IllegalArgumentException("plan limits are invalid");
        }
        this.maxTasks = maxTasks;
        this.maxPlanSteps = maxPlanSteps;
        this.maxRePlans = maxRePlans;
        this.maxParallelism = maxParallelism;
        this.totalTimeoutMillis = totalTimeoutMillis;
        this.totalBudget = totalBudget;
    }
}
