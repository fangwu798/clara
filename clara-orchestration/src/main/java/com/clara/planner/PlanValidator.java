package com.clara.planner;

import com.clara.kernel.exception.ErrorCategory;
import com.clara.kernel.exception.ErrorCodes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 校验计划是否符合约束。
 */
public final class PlanValidator {
    private final PlanLimits limits;
    private final CapabilityAuthorizer authorizer;
    public PlanValidator(PlanLimits limits, CapabilityAuthorizer authorizer) {
        if (limits == null || authorizer == null) { throw new IllegalArgumentException("limits and authorizer are required"); }
        this.limits = limits; this.authorizer = authorizer;
    }
    public TaskPlan validate(TaskPlan plan) {
        if (plan == null) { throw new IllegalArgumentException("plan must not be null"); }
        if (plan.getTasks().size() > limits.getMaxTasks()
                || plan.getTasks().size() > limits.getMaxPlanSteps()) {
            throw PlannerErrors.error(ErrorCodes.PLAN_LIMIT_EXCEEDED, ErrorCategory.RESOURCE_LIMIT);
        }
        Map<String, TaskNode> tasks = new HashMap<String, TaskNode>();
        Set<String> outputs = new HashSet<String>();
        Set<String> idempotencyKeys = new HashSet<String>();
        BudgetAmount maximum = BudgetAmount.ZERO;
        for (TaskNode task : plan.getTasks()) {
            if (tasks.put(task.getTaskId(), task) != null || !outputs.add(task.getOutputKey())
                    || !idempotencyKeys.add(task.getIdempotencyKey())) invalid();
            if (task.getCapability() != null && !authorizer.isAllowed(task.getCapability())) {
                throw PlannerErrors.error(ErrorCodes.PLAN_CAPABILITY_DENIED, ErrorCategory.AUTHORIZATION);
            }
            maximum = maximum.plus(task.getMaximumBudget());
        }
        if (!limits.getTotalBudget().covers(maximum)) {
            throw PlannerErrors.error(ErrorCodes.BUDGET_EXCEEDED, ErrorCategory.RESOURCE_LIMIT);
        }
        for (TaskNode task : plan.getTasks()) {
            for (String dependency : task.getDependencies()) {
                if (!tasks.containsKey(dependency) || dependency.equals(task.getTaskId())) invalid();
            }
        }
        Map<String, Integer> colors = new HashMap<String, Integer>();
        for (TaskNode task : plan.getTasks()) visit(task, tasks, colors);
        return plan.withStatus(PlanStatus.VALIDATED);
    }
    public PlanLimits getLimits() { return limits; }
    private static void visit(TaskNode task, Map<String, TaskNode> tasks, Map<String, Integer> colors) {
        Integer color = colors.get(task.getTaskId());
        if (Integer.valueOf(1).equals(color)) invalid();
        if (Integer.valueOf(2).equals(color)) return;
        colors.put(task.getTaskId(), Integer.valueOf(1));
        for (String dependency : task.getDependencies()) visit(tasks.get(dependency), tasks, colors);
        colors.put(task.getTaskId(), Integer.valueOf(2));
    }
    private static void invalid() {
        throw PlannerErrors.error(ErrorCodes.PLAN_INVALID, ErrorCategory.VALIDATION);
    }
}
