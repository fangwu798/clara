package com.clara.planner;

public final class PlanLimits {
    private final int maxTasks;
    private final int maxPlanSteps;
    private final int maxReplans;
    private final int maxParallelism;
    private final long totalTimeoutMillis;
    private final BudgetAmount totalBudget;
    public PlanLimits(int maxTasks, int maxPlanSteps, int maxReplans, int maxParallelism,
            long totalTimeoutMillis, BudgetAmount totalBudget) {
        if (maxTasks <= 0 || maxPlanSteps <= 0 || maxReplans < 0 || maxParallelism <= 0
                || totalTimeoutMillis <= 0L || totalBudget == null) {
            throw new IllegalArgumentException("plan limits are invalid");
        }
        this.maxTasks = maxTasks; this.maxPlanSteps = maxPlanSteps; this.maxReplans = maxReplans;
        this.maxParallelism = maxParallelism; this.totalTimeoutMillis = totalTimeoutMillis;
        this.totalBudget = totalBudget;
    }
    public int getMaxTasks() { return maxTasks; }
    public int getMaxPlanSteps() { return maxPlanSteps; }
    public int getMaxReplans() { return maxReplans; }
    public int getMaxParallelism() { return maxParallelism; }
    public long getTotalTimeoutMillis() { return totalTimeoutMillis; }
    public BudgetAmount getTotalBudget() { return totalBudget; }
}
